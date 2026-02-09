/*
 *  (C) 2017-2026 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.gateclients.v2;

import build.buf.gen.tcnapi.exile.gate.v2.Event;
import build.buf.gen.tcnapi.exile.gate.v2.EventStreamRequest;
import build.buf.gen.tcnapi.exile.gate.v2.EventStreamResponse;
import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Streams events from Gate using the EventStream API with acknowledgment. */
public class GateClientEventStream extends GateClientAbstract {
  protected static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GateClientEventStream.class);

  private static final int BATCH_SIZE = 100;
  private static final long REQUEST_TIMEOUT_SECONDS = 60;

  private final PluginInterface plugin;

  // Track ACKs between polling cycles - events successfully processed
  // will be ACKed in the next request
  private final List<String> pendingAcks = new ArrayList<>();

  public GateClientEventStream(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  @Override
  public void start() {
    try {
      if (isUnconfigured()) {
        log.debug("Tenant: {} - Configuration not set, skipping event stream", tenant);
        return;
      }
      if (!plugin.isRunning()) {
        log.debug(
            "Tenant: {} - Plugin is not running (possibly due to database disconnection), skipping event stream",
            tenant);
        return;
      }

      int totalProcessed = 0;
      long cycleStart = System.currentTimeMillis();

      // Copy pending ACKs and clear for this cycle
      List<String> acksToSend;
      synchronized (pendingAcks) {
        acksToSend = new ArrayList<>(pendingAcks);
        pendingAcks.clear();
      }

      if (!acksToSend.isEmpty()) {
        log.debug("Tenant: {} - Sending {} ACKs from previous cycle", tenant, acksToSend.size());
      }

      // Request events, sending any pending ACKs from previous cycle
      EventStreamResponse response = requestEvents(acksToSend);

      if (response == null || response.getEventsCount() == 0) {
        log.debug(
            "Tenant: {} - Event stream request completed successfully but no events were received",
            tenant);
        return;
      }

      log.debug("Tenant: {} - Received {} events from stream", tenant, response.getEventsCount());

      // Process events and collect successful ACK IDs
      List<String> successfulAcks = new ArrayList<>();
      long batchStart = System.currentTimeMillis();

      for (Event event : response.getEventsList()) {
        String eventId = getEventId(event);
        if (processEvent(event)) {
          successfulAcks.add(eventId);
        }
        // If processing fails, event is NOT added to ACKs - it will be redelivered
      }

      long batchEnd = System.currentTimeMillis();
      totalProcessed = successfulAcks.size();

      // Store successful ACKs for next cycle
      synchronized (pendingAcks) {
        pendingAcks.addAll(successfulAcks);
      }

      // Warn if batch processing is slow
      if (totalProcessed > 0) {
        long avg = (batchEnd - batchStart) / totalProcessed;
        if (avg > 1000) {
          log.warn(
              "Tenant: {} - Event stream batch completed {} events in {}ms, average time per event: {}ms, this is long",
              tenant,
              totalProcessed,
              batchEnd - batchStart,
              avg);
        }
      }

      // Log summary
      if (totalProcessed > 0) {
        long cycleEnd = System.currentTimeMillis();
        log.info(
            "Tenant: {} - Event stream cycle completed, processed {} events in {}ms",
            tenant,
            totalProcessed,
            cycleEnd - cycleStart);
      }

    } catch (UnconfiguredException e) {
      log.error("Tenant: {} - Error while getting client configuration {}", tenant, e.getMessage());
    } catch (InterruptedException e) {
      log.warn("Tenant: {} - Event stream interrupted", tenant);
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error("Tenant: {} - Unexpected error in event stream", tenant, e);
      // Clear pending ACKs on error to avoid ACKing events we may not have processed
      synchronized (pendingAcks) {
        pendingAcks.clear();
      }
    }
  }

  /**
   * Request events using async stub with blocking wait. This handles the server-streaming pattern
   * where we send request + StreamObserver.
   */
  private EventStreamResponse requestEvents(List<String> ackEventIds)
      throws InterruptedException, UnconfiguredException {

    var request =
        EventStreamRequest.newBuilder()
            .setEventCount(BATCH_SIZE)
            .addAllAckEventIds(ackEventIds)
            .build();

    var latch = new CountDownLatch(1);
    var responseRef = new AtomicReference<EventStreamResponse>();
    var errorRef = new AtomicReference<Throwable>();

    GateServiceGrpc.newStub(getChannel())
        .eventStream(
            request,
            new StreamObserver<EventStreamResponse>() {
              @Override
              public void onNext(EventStreamResponse response) {
                responseRef.set(response);
              }

              @Override
              public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
              }

              @Override
              public void onCompleted() {
                latch.countDown();
              }
            });

    // Wait for response with timeout
    boolean completed = latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    if (!completed) {
      throw new RuntimeException(
          "Event stream request timed out after " + REQUEST_TIMEOUT_SECONDS + " seconds");
    }

    if (errorRef.get() != null) {
      throw new RuntimeException("Event stream error", errorRef.get());
    }

    var response = responseRef.get();
    if (response == null) {
      // Return empty response if none received
      return EventStreamResponse.getDefaultInstance();
    }
    return response;
  }

  /**
   * Process a single event. Returns true if successfully processed (should ACK).
   *
   * <p>This mirrors the event handling logic from GateClientPollEvents exactly.
   */
  private boolean processEvent(Event event) {
    try {
      if (event.hasAgentCall()) {
        log.debug(
            "Tenant: {} - Received agent call event {} - {}",
            tenant,
            event.getAgentCall().getCallSid(),
            event.getAgentCall().getCallType());
        plugin.handleAgentCall(event.getAgentCall());
      }

      if (event.hasAgentResponse()) {
        log.debug(
            "Tenant: {} - Received agent response event {}",
            tenant,
            event.getAgentResponse().getAgentCallResponseSid());
        plugin.handleAgentResponse(event.getAgentResponse());
      }

      if (event.hasTelephonyResult()) {
        log.debug(
            "Tenant: {} - Received telephony result event {} - {}",
            tenant,
            event.getTelephonyResult().getCallSid(),
            event.getTelephonyResult().getCallType());
        plugin.handleTelephonyResult(event.getTelephonyResult());
      }

      if (event.hasTask()) {
        log.debug("Tenant: {} - Received task event {}", tenant, event.getTask().getTaskSid());
        plugin.handleTask(event.getTask());
      }

      if (event.hasTransferInstance()) {
        log.debug(
            "Tenant: {} - Received transfer instance event {}",
            tenant,
            event.getTransferInstance().getTransferInstanceId());
        plugin.handleTransferInstance(event.getTransferInstance());
      }

      if (event.hasCallRecording()) {
        log.debug(
            "Tenant: {} - Received call recording event {}",
            tenant,
            event.getCallRecording().getRecordingId());
        plugin.handleCallRecording(event.getCallRecording());
      }

      return true;

    } catch (Exception e) {
      log.error("Tenant: {} - Failed to process event: {}", tenant, e.getMessage(), e);
      return false; // Don't ACK - will be redelivered
    }
  }

  /** Extract event ID for ACK purposes. Each event type has a different primary identifier. */
  private String getEventId(Event event) {
    if (event.hasAgentCall()) {
      return String.valueOf(event.getAgentCall().getAgentCallSid());
    }
    if (event.hasTelephonyResult()) {
      return String.valueOf(event.getTelephonyResult().getCallSid());
    }
    if (event.hasAgentResponse()) {
      return String.valueOf(event.getAgentResponse().getAgentCallResponseSid());
    }
    if (event.hasTask()) {
      return String.valueOf(event.getTask().getTaskSid());
    }
    if (event.hasTransferInstance()) {
      return event.getTransferInstance().getTransferInstanceId();
    }
    if (event.hasCallRecording()) {
      return event.getCallRecording().getRecordingId();
    }
    log.warn("Tenant: {} - Unknown event type, cannot extract ID", tenant);
    return "unknown";
  }

  /** Get count of pending ACKs (for diagnostics). */
  public int getPendingAckCount() {
    synchronized (pendingAcks) {
      return pendingAcks.size();
    }
  }
}
