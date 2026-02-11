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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional event streaming with immediate acknowledgment using EventStream API.
 *
 * <p>The client drives the loop: sends a request (with ACK IDs + event count), server responds with
 * events, client processes them and sends the next request with ACKs. This repeats until the
 * server's 5-minute context timeout expires.
 */
public class GateClientEventStream extends GateClientAbstract {
  private static final StructuredLogger log = new StructuredLogger(GateClientEventStream.class);

  private static final int BATCH_SIZE = 100;
  private static final long STREAM_TIMEOUT_MINUTES = 5;
  private static final long HUNG_CONNECTION_THRESHOLD_SECONDS = 45;

  // Backoff configuration
  private static final long BACKOFF_BASE_MS = 2000;
  private static final long BACKOFF_MAX_MS = 30000;
  private static final double BACKOFF_JITTER = 0.2;

  private final PluginInterface plugin;
  private final AtomicBoolean establishedForCurrentAttempt = new AtomicBoolean(false);
  private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>();

  // Connection timing tracking
  private final AtomicReference<Instant> lastDisconnectTime = new AtomicReference<>();
  private final AtomicReference<Instant> reconnectionStartTime = new AtomicReference<>();
  private final AtomicReference<Instant> connectionEstablishedTime = new AtomicReference<>();
  private final AtomicLong totalReconnectionAttempts = new AtomicLong(0);
  private final AtomicLong successfulReconnections = new AtomicLong(0);
  private final AtomicReference<String> lastErrorType = new AtomicReference<>();
  private final AtomicLong consecutiveFailures = new AtomicLong(0);
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicLong eventsProcessed = new AtomicLong(0);
  private final AtomicLong eventsFailed = new AtomicLong(0);

  // Pending ACKs buffer - survives stream reconnections.
  // Event IDs are added here after successful processing and removed only after
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

  /** Check if the connection is hung (no messages received in threshold time). */
  private void checkForHungConnection() throws HungConnectionException {
    Instant lastMsg = lastMessageTime.get();
    if (lastMsg == null) {
      lastMsg = connectionEstablishedTime.get();
    }
    if (lastMsg != null
        && lastMsg.isBefore(
            Instant.now().minus(HUNG_CONNECTION_THRESHOLD_SECONDS, ChronoUnit.SECONDS))) {
      throw new HungConnectionException(
          "No messages received since " + lastMsg + " - connection appears hung");
    }
  }

  /** Compute backoff delay with jitter based on consecutive failure count. */
  private long computeBackoffMs() {
    long failures = consecutiveFailures.get();
    if (failures <= 0) {
      return 0;
    }
    // Exponential: 2s, 4s, 8s, 16s, capped at 30s
    long delayMs = BACKOFF_BASE_MS * (1L << Math.min(failures - 1, 10));
    // Add ±20% jitter to avoid thundering herd between streams
    double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
    return Math.min((long) (delayMs * jitter), BACKOFF_MAX_MS);
  }

  @Override
  public void start() {
    if (isUnconfigured()) {
      log.warn(LogCategory.GRPC, "NOOP", "EventStream is unconfigured, cannot stream events");
      return;
    }

    // Prevent concurrent invocations from the fixed-rate scheduler
    if (!isRunning.compareAndSet(false, true)) {
      return;
    }

    // Exponential backoff: sleep before retrying if we have consecutive failures
    long backoffMs = computeBackoffMs();
    if (backoffMs > 0) {
      log.debug(
          LogCategory.GRPC,
          "Backoff",
          "Waiting %dms before reconnect attempt (consecutive failures: %d)",
          backoffMs,
          consecutiveFailures.get());
      try {
        Thread.sleep(backoffMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        isRunning.set(false);
        return;
      }
    }

    try {
      log.debug(
          LogCategory.GRPC, "Init", "EventStream task started, checking configuration status");

      reconnectionStartTime.set(Instant.now());

      // Reset gRPC's internal DNS backoff so name resolution is retried immediately
      resetChannelBackoff();

      runStream();

    } catch (HungConnectionException e) {
      log.warn(LogCategory.GRPC, "HungConnection", "Event stream appears hung: %s", e.getMessage());
      lastErrorType.set("HungConnection");
      consecutiveFailures.incrementAndGet();
    } catch (UnconfiguredException e) {
      log.error(LogCategory.GRPC, "ConfigError", "Configuration error: %s", e.getMessage());
      lastErrorType.set("UnconfiguredException");
      consecutiveFailures.incrementAndGet();
    } catch (InterruptedException e) {
      log.info(LogCategory.GRPC, "Interrupted", "Event stream interrupted");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "EventStream",
          "Error streaming events from server: %s",
          e.getMessage(),
          e);
      lastErrorType.set(e.getClass().getSimpleName());
      consecutiveFailures.incrementAndGet();
    } finally {
      totalReconnectionAttempts.incrementAndGet();
      lastDisconnectTime.set(Instant.now());
      isRunning.set(false);
      requestObserverRef.set(null);
      if (connectionEstablishedTime.get() != null) {
        log.debug(LogCategory.GRPC, "Complete", "Event stream done");
      }
    }
  }

  /** Run a single stream session with bidirectional communication. */
  private void runStream()
      throws UnconfiguredException, InterruptedException, HungConnectionException {
    var latch = new CountDownLatch(1);
    var errorRef = new AtomicReference<Throwable>();
    var firstResponseReceived = new AtomicBoolean(false);

    // Create response handler
    var responseObserver =
        new StreamObserver<EventStreamResponse>() {
          @Override
          public void onNext(EventStreamResponse response) {
            lastMessageTime.set(Instant.now());

            // Log connected on first server response
            if (firstResponseReceived.compareAndSet(false, true)) {
              connectionEstablishedTime.set(Instant.now());
              successfulReconnections.incrementAndGet();
              consecutiveFailures.set(0);
              lastErrorType.set(null);

              log.info(
                  LogCategory.GRPC,
                  "ConnectionEstablished",
                  "Event stream connection took %s",
                  Duration.between(reconnectionStartTime.get(), connectionEstablishedTime.get()));
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
              // Still send next request to keep the loop alive
              sendNextRequest();
              return;
            }

            log.debug(
                LogCategory.GRPC,
                "EventsReceived",
                "Received %d events from stream",
                response.getEventsCount());

            // Process events and store successful ACK IDs in the pending buffer
            long batchStart = System.currentTimeMillis();
            int batchSuccessCount = 0;

            for (Event event : response.getEventsList()) {
              String eventId = getEventId(event);
              if (processEvent(event)) {
                synchronized (pendingAcks) {
                  pendingAcks.add(eventId);
                }
                batchSuccessCount++;
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

            long batchEnd = System.currentTimeMillis();

            // Warn if batch processing is slow
            if (batchSuccessCount > 0) {
              long avg = (batchEnd - batchStart) / batchSuccessCount;
              if (avg > 1000) {
                log.warn(
                    LogCategory.GRPC,
                    "SlowBatch",
                    "Event batch completed %d events in %dms, avg %dms per event",
                    batchSuccessCount,
                    batchEnd - batchStart,
                    avg);
              }
            }

            // Send next request with ACKs immediately - drains pendingAcks on success
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

    // Send initial request - include any pending ACKs from a previous broken stream
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
    // Clear pending ACKs only after successful initial send
    if (!carryOverAcks.isEmpty()) {
      synchronized (pendingAcks) {
        pendingAcks.removeAll(carryOverAcks);
      }
    }

    // Wait for stream to complete, with periodic hung-connection checks
    boolean completed = false;
    long startTime = System.currentTimeMillis();
    long maxDurationMs = TimeUnit.MINUTES.toMillis(STREAM_TIMEOUT_MINUTES);

    while (!completed && (System.currentTimeMillis() - startTime) < maxDurationMs) {
      completed = latch.await(HUNG_CONNECTION_THRESHOLD_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        // Check for hung connection
        checkForHungConnection();
      }
    }

    // Propagate error if any
    var error = errorRef.get();
    if (error != null) {
      throw new RuntimeException("Stream error", error);
    }
  }

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

    // Drain pending ACKs
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

      // Only clear after successful send
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
      // ACKs stay in pendingAcks - will be retried on next send or reconnection
      log.error(
          LogCategory.GRPC,
          "RequestFailed",
          "Failed to send next event stream request (keeping %d pending ACKs): %s",
          acksToSend.size(),
          e.getMessage());
    }
  }

  /**
   * Process a single event. Returns true if successfully processed (should ACK).
   *
   * <p>This mirrors the event handling logic from GateClientPollEvents exactly.
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

      if (event.hasAgentCall()) {
        log.debug(
            LogCategory.GRPC,
            "EventProcessed",
            "Received agent call event %d - %s",
            event.getAgentCall().getCallSid(),
            event.getAgentCall().getCallType());
        plugin.handleAgentCall(event.getAgentCall());
      }

      if (event.hasAgentResponse()) {
        log.debug(
            LogCategory.GRPC,
            "EventProcessed",
            "Received agent response event %s",
            event.getAgentResponse().getAgentCallResponseSid());
        plugin.handleAgentResponse(event.getAgentResponse());
      }

      if (event.hasTelephonyResult()) {
        log.debug(
            LogCategory.GRPC,
            "EventProcessed",
            "Received telephony result event %d - %s",
            event.getTelephonyResult().getCallSid(),
            event.getTelephonyResult().getCallType());
        plugin.handleTelephonyResult(event.getTelephonyResult());
      }

      if (event.hasTask()) {
        log.debug(
            LogCategory.GRPC,
            "EventProcessed",
            "Received task event %s",
            event.getTask().getTaskSid());
        plugin.handleTask(event.getTask());
      }

      if (event.hasTransferInstance()) {
        log.debug(
            LogCategory.GRPC,
            "EventProcessed",
            "Received transfer instance event %s",
            event.getTransferInstance().getTransferInstanceId());
        plugin.handleTransferInstance(event.getTransferInstance());
      }

      if (event.hasCallRecording()) {
        log.debug(
            LogCategory.GRPC,
            "EventProcessed",
            "Received call recording event %s",
            event.getCallRecording().getRecordingId());
        plugin.handleCallRecording(event.getCallRecording());
      }

      return true;

    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "EventProcessingError",
          "Failed to process event: %s",
          e.getMessage(),
          e);
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
    log.warn(LogCategory.GRPC, "UnknownEvent", "Unknown event type, cannot extract ID");
    return "unknown";
  }

  public void stop() {
    log.info(
        LogCategory.GRPC,
        "Stopping",
        "Stopping GateClientEventStream (total attempts: %d, successful: %d, events: %d/%d)",
        totalReconnectionAttempts.get(),
        successfulReconnections.get(),
        eventsProcessed.get(),
        eventsFailed.get());

    isRunning.set(false);

    // Log any un-sent ACKs so they're visible for debugging redelivery
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

    // Gracefully close the stream
    var observer = requestObserverRef.get();
    if (observer != null) {
      try {
        observer.onCompleted();
      } catch (Exception e) {
        log.debug(LogCategory.GRPC, "CloseError", "Error closing stream: %s", e.getMessage());
      }
    }

    shutdown();
    log.info(LogCategory.GRPC, "GateClientEventStreamStopped", "GateClientEventStream stopped");
  }

  @PreDestroy
  public void destroy() {
    log.info(
        LogCategory.GRPC,
        "GateClientEventStream@PreDestroyCalled",
        "GateClientEventStream @PreDestroy called");
    stop();
  }

  public Map<String, Object> getStreamStatus() {
    Instant lastDisconnect = lastDisconnectTime.get();
    Instant connectionEstablished = connectionEstablishedTime.get();
    Instant reconnectStart = reconnectionStartTime.get();
    Instant lastMessage = lastMessageTime.get();

    Map<String, Object> status = new HashMap<>();
    status.put("isRunning", isRunning.get());
    status.put("totalReconnectionAttempts", totalReconnectionAttempts.get());
    status.put("successfulReconnections", successfulReconnections.get());
    status.put("consecutiveFailures", consecutiveFailures.get());
    status.put("eventsProcessed", eventsProcessed.get());
    status.put("eventsFailed", eventsFailed.get());
    synchronized (pendingAcks) {
      status.put("pendingAckCount", pendingAcks.size());
    }
    status.put("lastDisconnectTime", lastDisconnect != null ? lastDisconnect.toString() : null);
    status.put(
        "connectionEstablishedTime",
        connectionEstablished != null ? connectionEstablished.toString() : null);
    status.put("reconnectionStartTime", reconnectStart != null ? reconnectStart.toString() : null);
    status.put("lastErrorType", lastErrorType.get());
    status.put("lastMessageTime", lastMessage != null ? lastMessage.toString() : null);

    return status;
  }

  /** Exception for hung connection detection. */
  public static class HungConnectionException extends Exception {
    public HungConnectionException(String message) {
      super(message);
    }
  }
}
