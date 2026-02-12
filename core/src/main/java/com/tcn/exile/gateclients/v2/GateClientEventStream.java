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
import com.tcn.exile.log.LogCategory;
import com.tcn.exile.log.StructuredLogger;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional event streaming with acknowledgment using the EventStream API.
 *
 * <p>The client drives the loop: sends a request (with ACK IDs + event count), server responds with
 * events, client processes them and sends the next request with ACKs. This repeats until the
 * server's 5-minute context timeout expires.
 */
public class GateClientEventStream extends GateClientAbstract {
  private static final StructuredLogger log = new StructuredLogger(GateClientEventStream.class);

  private static final int BATCH_SIZE = 100;

  private final PluginInterface plugin;
  private final AtomicLong eventsProcessed = new AtomicLong(0);
  private final AtomicLong eventsFailed = new AtomicLong(0);

  // Pending ACKs buffer — survives stream reconnections.
  // Event IDs are added after successful processing and removed only after
  // successful send to the server. If a stream breaks before ACKs are sent,
  // they carry over to the next connection attempt.
  private final List<String> pendingAcks = new ArrayList<>();

  // Stream observer for sending requests/ACKs
  private final AtomicReference<StreamObserver<EventStreamRequest>> requestObserverRef =
      new AtomicReference<>();

  public GateClientEventStream(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  @Override
  protected String getStreamName() {
    return "EventStream";
  }

  @Override
  protected void onStreamDisconnected() {
    requestObserverRef.set(null);
  }

  @Override
  protected void runStream()
      throws UnconfiguredException, InterruptedException, HungConnectionException {
    var latch = new CountDownLatch(1);
    var errorRef = new AtomicReference<Throwable>();
    var firstResponseReceived = new AtomicBoolean(false);

    var responseObserver =
        new StreamObserver<EventStreamResponse>() {
          @Override
          public void onNext(EventStreamResponse response) {
            lastMessageTime.set(Instant.now());

            if (firstResponseReceived.compareAndSet(false, true)) {
              onConnectionEstablished();
            }

            if (response.getEventsCount() == 0) {
              log.debug(
                  LogCategory.GRPC, "EmptyBatch", "Received empty event batch, requesting next");
              // Brief pause to avoid hot-looping when no events are available.
              // The server responds instantly to empty polls, so without this
              // delay we'd loop every ~40ms burning CPU and network.
              try {
                Thread.sleep(10000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              sendNextRequest();
              return;
            }

            log.debug(
                LogCategory.GRPC,
                "EventsReceived",
                "Received %d events from stream",
                response.getEventsCount());

            processBatch(response.getEventsList());
            sendNextRequest();
          }

          @Override
          public void onError(Throwable t) {
            log.warn(LogCategory.GRPC, "StreamError", "Event stream error: %s", t.getMessage());
            errorRef.set(t);
            latch.countDown();
          }

          @Override
          public void onCompleted() {
            log.info(LogCategory.GRPC, "StreamCompleted", "Event stream completed by server");
            latch.countDown();
          }
        };

    // Open bidirectional stream
    var requestObserver = GateServiceGrpc.newStub(getChannel()).eventStream(responseObserver);
    requestObserverRef.set(requestObserver);

    // Send initial request — include any pending ACKs from a previous broken stream
    List<String> carryOverAcks;
    synchronized (pendingAcks) {
      carryOverAcks = new ArrayList<>(pendingAcks);
    }
    if (!carryOverAcks.isEmpty()) {
      log.info(
          LogCategory.GRPC,
          "CarryOverAcks",
          "Sending %d pending ACKs from previous stream session",
          carryOverAcks.size());
    }
    log.debug(LogCategory.GRPC, "Init", "Sending initial event stream request...");
    requestObserver.onNext(
        EventStreamRequest.newBuilder()
            .setEventCount(BATCH_SIZE)
            .addAllAckEventIds(carryOverAcks)
            .build());
    if (!carryOverAcks.isEmpty()) {
      synchronized (pendingAcks) {
        pendingAcks.removeAll(carryOverAcks);
      }
    }

    awaitStreamWithHungDetection(latch);

    var error = errorRef.get();
    if (error != null) {
      throw new RuntimeException("Stream error", error);
    }
  }

  // ---------------------------------------------------------------------------
  // Request & ACK management
  // ---------------------------------------------------------------------------

  /**
   * Send the next request on the stream, draining pendingAcks into the request. If the send fails,
   * the ACKs remain in pendingAcks for the next attempt or reconnection.
   */
  private void sendNextRequest() {
    var observer = requestObserverRef.get();
    if (observer == null) {
      log.warn(LogCategory.GRPC, "RequestFailed", "Cannot send next request - no active observer");
      return;
    }

    List<String> acksToSend;
    synchronized (pendingAcks) {
      acksToSend = new ArrayList<>(pendingAcks);
    }

    try {
      var request =
          EventStreamRequest.newBuilder()
              .setEventCount(BATCH_SIZE)
              .addAllAckEventIds(acksToSend)
              .build();
      observer.onNext(request);

      if (!acksToSend.isEmpty()) {
        synchronized (pendingAcks) {
          pendingAcks.removeAll(acksToSend);
        }
        log.debug(
            LogCategory.GRPC,
            "AckSent",
            "Sent ACK for %d events, requesting next batch",
            acksToSend.size());
      }
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "RequestFailed",
          "Failed to send next event stream request (keeping %d pending ACKs): %s",
          acksToSend.size(),
          e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Event processing
  // ---------------------------------------------------------------------------

  /** Process a batch of events, adding successful ACK IDs to the pending buffer. */
  private void processBatch(List<Event> events) {
    long batchStart = System.currentTimeMillis();
    int successCount = 0;

    for (Event event : events) {
      String eventId = getEventId(event);
      if (processEvent(event)) {
        synchronized (pendingAcks) {
          pendingAcks.add(eventId);
        }
        successCount++;
        eventsProcessed.incrementAndGet();
      } else {
        eventsFailed.incrementAndGet();
        log.warn(
            LogCategory.GRPC,
            "EventNotAcked",
            "Event %s NOT acknowledged - will be redelivered",
            eventId);
      }
    }

    long elapsed = System.currentTimeMillis() - batchStart;
    if (successCount > 0 && (elapsed / successCount) > 1000) {
      log.warn(
          LogCategory.GRPC,
          "SlowBatch",
          "Event batch completed %d events in %dms, avg %dms per event",
          successCount,
          elapsed,
          elapsed / successCount);
    }
  }

  /**
   * Process a single event by dispatching to the plugin. Returns true if successfully processed.
   */
  private boolean processEvent(Event event) {
    try {
      if (!plugin.isRunning()) {
        log.debug(
            LogCategory.GRPC,
            "PluginNotRunning",
            "Plugin is not running, skipping event processing");
        return false;
      }

      switch (event.getEntityCase()) {
        case AGENT_CALL:
          log.debug(
              LogCategory.GRPC,
              "EventProcessed",
              "Received agent call event %d - %s",
              event.getAgentCall().getCallSid(),
              event.getAgentCall().getCallType());
          plugin.handleAgentCall(event.getAgentCall());
          break;
        case AGENT_RESPONSE:
          log.debug(
              LogCategory.GRPC,
              "EventProcessed",
              "Received agent response event %s",
              event.getAgentResponse().getAgentCallResponseSid());
          plugin.handleAgentResponse(event.getAgentResponse());
          break;
        case TELEPHONY_RESULT:
          log.debug(
              LogCategory.GRPC,
              "EventProcessed",
              "Received telephony result event %d - %s",
              event.getTelephonyResult().getCallSid(),
              event.getTelephonyResult().getCallType());
          plugin.handleTelephonyResult(event.getTelephonyResult());
          break;
        case TASK:
          log.debug(
              LogCategory.GRPC,
              "EventProcessed",
              "Received task event %s",
              event.getTask().getTaskSid());
          plugin.handleTask(event.getTask());
          break;
        case TRANSFER_INSTANCE:
          log.debug(
              LogCategory.GRPC,
              "EventProcessed",
              "Received transfer instance event %s",
              event.getTransferInstance().getTransferInstanceId());
          plugin.handleTransferInstance(event.getTransferInstance());
          break;
        case CALL_RECORDING:
          log.debug(
              LogCategory.GRPC,
              "EventProcessed",
              "Received call recording event %s",
              event.getCallRecording().getRecordingId());
          plugin.handleCallRecording(event.getCallRecording());
          break;
        default:
          log.warn(
              LogCategory.GRPC, "UnknownEvent", "Unknown event type: %s", event.getEntityCase());
          break;
      }

      return true;
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "EventProcessingError",
          "Failed to process event: %s",
          e.getMessage(),
          e);
      return false;
    }
  }

  /**
   * Extract the server-assigned entity ID for ACK purposes. The server sets exile_entity_id on each
   * Event from the DB's entity_id, which may be a composite key (e.g. "call_type,call_sid" for
   * telephony). We must echo this exact value back for the ACK to match.
   */
  private String getEventId(Event event) {
    String entityId = event.getExileEntityId();
    if (entityId == null || entityId.isEmpty()) {
      log.warn(
          LogCategory.GRPC,
          "MissingEntityId",
          "Event has no exile_entity_id, cannot ACK. Event type: %s",
          event.getEntityCase());
      return "unknown";
    }
    return entityId;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void stop() {
    log.info(
        LogCategory.GRPC,
        "Stopping",
        "Stopping GateClientEventStream (total attempts: %d, successful: %d, events: %d/%d)",
        totalReconnectionAttempts.get(),
        successfulReconnections.get(),
        eventsProcessed.get(),
        eventsFailed.get());

    synchronized (pendingAcks) {
      if (!pendingAcks.isEmpty()) {
        log.warn(
            LogCategory.GRPC,
            "UnsentAcks",
            "Shutting down with %d un-sent ACKs (these events will be redelivered): %s",
            pendingAcks.size(),
            pendingAcks);
      }
    }

    var observer = requestObserverRef.get();
    if (observer != null) {
      try {
        observer.onCompleted();
      } catch (Exception e) {
        log.debug(LogCategory.GRPC, "CloseError", "Error closing stream: %s", e.getMessage());
      }
    }

    doStop();
    log.info(LogCategory.GRPC, "Stopped", "GateClientEventStream stopped");
  }

  @PreDestroy
  public void destroy() {
    stop();
  }

  public Map<String, Object> getStreamStatus() {
    Map<String, Object> status = buildStreamStatus();
    status.put("eventsProcessed", eventsProcessed.get());
    status.put("eventsFailed", eventsFailed.get());
    synchronized (pendingAcks) {
      status.put("pendingAckCount", pendingAcks.size());
    }
    return status;
  }
}
