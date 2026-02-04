package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v2.*;
import com.tcn.sati.infra.backend.TenantBackendClient;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams events from Gate using the EventStream API with acknowledgment.
 * 
 * The Java stub uses a server-streaming pattern where:
 * 1. Client sends request with event_count and ACK list
 * 2. Server streams back events
 * 3. Client processes events, collects ACK IDs
 * 4. Client sends next request with new ACK list
 * 
 * Server has a 5-minute timeout per stream connection.
 */
public class EventStreamClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EventStreamClient.class);

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final long RECONNECT_DELAY_MS = 5_000;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;
    private static final long POLL_INTERVAL_MS = 1_000;

    private final GateClient gateClient;
    private final TenantBackendClient backendClient;
    private final int batchSize;
    private final Thread streamThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong eventsFailed = new AtomicLong(0);

    public EventStreamClient(GateClient gateClient, TenantBackendClient backendClient) {
        this(gateClient, backendClient, DEFAULT_BATCH_SIZE);
    }

    public EventStreamClient(GateClient gateClient, TenantBackendClient backendClient, int batchSize) {
        this.gateClient = gateClient;
        this.backendClient = backendClient;
        this.batchSize = batchSize;
        this.streamThread = new Thread(this::eventLoop, "event-stream");
        this.streamThread.setDaemon(true);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting event stream client (batch size: {})", batchSize);
            streamThread.start();
        }
    }

    private void eventLoop() {
        List<String> pendingAcks = new ArrayList<>();
        long currentDelay = RECONNECT_DELAY_MS;
        boolean firstResponse = true;

        while (running.get()) {
            try {
                // Skip if backend not connected
                if (!backendClient.isConnected()) {
                    log.debug("Backend not connected, waiting...");
                    sleep(currentDelay);
                    continue;
                }

                // Request events (server-streaming pattern)
                log.debug("Requesting events from Gate...");
                var response = requestEvents(pendingAcks);

                // Log connected only on first successful response
                if (firstResponse) {
                    log.info("✅ Connected to event stream (received server response)");
                    firstResponse = false;
                }

                pendingAcks.clear();
                currentDelay = RECONNECT_DELAY_MS; // Reset on success

                var events = response.getEventsList();
                if (events.isEmpty()) {
                    // No events - brief pause before next poll
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }

                log.debug("Received {} events", events.size());

                // Process each event, track successful ACKs
                for (var event : events) {
                    String eventId = getEventId(event);
                    if (processEvent(event)) {
                        pendingAcks.add(eventId);
                    }
                }

            } catch (Exception e) {
                firstResponse = true; // Will log reconnection on next success
                log.warn("Event stream error: {} - retrying in {}s",
                        e.getMessage(), currentDelay / 1000);
                pendingAcks.clear(); // Don't ACK events we may not have processed
                sleep(currentDelay);
                currentDelay = Math.min(currentDelay * 2, MAX_RECONNECT_DELAY_MS);
            }

            // Check for interruption
            if (Thread.currentThread().isInterrupted()) {
                log.info("Event stream interrupted");
                break;
            }
        }

        log.info("Event stream stopped (processed: {}, failed: {})",
                eventsProcessed.get(), eventsFailed.get());
    }

    /**
     * Request events using async stub with blocking wait.
     * This handles the server-streaming pattern where we send request +
     * StreamObserver.
     */
    private EventStreamResponse requestEvents(List<String> ackEventIds) throws InterruptedException {
        var request = EventStreamRequest.newBuilder()
                .setEventCount(batchSize)
                .addAllAckEventIds(ackEventIds)
                .build();

        var latch = new CountDownLatch(1);
        var responseRef = new AtomicReference<EventStreamResponse>();
        var errorRef = new AtomicReference<Throwable>();

        GateServiceGrpc.newStub(gateClient.getChannel())
                .eventStream(request, new StreamObserver<EventStreamResponse>() {
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
        latch.await();

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
     */
    private boolean processEvent(Event event) {
        try {
            if (event.hasAgentCall()) {
                backendClient.handleAgentCall(convertAgentCall(event.getAgentCall()));

            } else if (event.hasTelephonyResult()) {
                backendClient.handleTelephonyResult(convertTelephonyResult(event.getTelephonyResult()));

            } else if (event.hasAgentResponse()) {
                backendClient.handleAgentResponse(convertAgentResponse(event.getAgentResponse()));

            } else if (event.hasTask()) {
                backendClient.handleTask(convertTask(event.getTask()));

            } else if (event.hasTransferInstance()) {
                backendClient.handleTransferInstance(convertTransferInstance(event.getTransferInstance()));

            } else if (event.hasCallRecording()) {
                backendClient.handleCallRecording(convertCallRecording(event.getCallRecording()));

            } else {
                log.warn("Unknown event type, skipping");
                return true; // ACK unknown events to not block queue
            }

            eventsProcessed.incrementAndGet();
            return true;

        } catch (Exception e) {
            eventsFailed.incrementAndGet();
            log.error("Failed to process event: {}", e.getMessage());
            return false; // Don't ACK - will be redelivered
        }
    }

    // ========== Convert proto to DTOs ==========

    private TenantBackendClient.AgentCall convertAgentCall(ExileAgentCall proto) {
        return new TenantBackendClient.AgentCall(
                String.valueOf(proto.getAgentCallSid()),
                String.valueOf(proto.getCallSid()),
                proto.getUserId(),
                null);
    }

    private TenantBackendClient.TelephonyResult convertTelephonyResult(ExileTelephonyResult proto) {
        return new TenantBackendClient.TelephonyResult(
                String.valueOf(proto.getCallSid()),
                proto.getStatus().name(),
                proto.getResult().name(),
                null);
    }

    private TenantBackendClient.AgentResponse convertAgentResponse(ExileAgentResponse proto) {
        return new TenantBackendClient.AgentResponse(
                String.valueOf(proto.getAgentCallResponseSid()),
                String.valueOf(proto.getCallSid()),
                proto.getResponseKey(),
                proto.getResponseValue());
    }

    private TenantBackendClient.ExileTask convertTask(ExileTask proto) {
        return new TenantBackendClient.ExileTask(
                String.valueOf(proto.getTaskSid()),
                proto.getPoolId(),
                proto.getRecordId(),
                proto.getStatus().name());
    }

    private TenantBackendClient.TransferInstance convertTransferInstance(ExileTransferInstance proto) {
        return new TenantBackendClient.TransferInstance(
                proto.getTransferInstanceId(),
                proto.hasSource() && proto.getSource().hasCall()
                        ? String.valueOf(proto.getSource().getCall().getCallSid())
                        : "",
                proto.getTransferResult().name());
    }

    private TenantBackendClient.CallRecording convertCallRecording(ExileCallRecording proto) {
        return new TenantBackendClient.CallRecording(
                proto.getRecordingId(),
                String.valueOf(proto.getCallSid()),
                proto.getRecordingType().name());
    }

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
        return "unknown";
    }

    private void sleep(long ms) {
        try {
            if (running.get()) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getEventsProcessed() {
        return eventsProcessed.get();
    }

    public long getEventsFailed() {
        return eventsFailed.get();
    }

    @Override
    public void close() {
        log.info("Shutting down event stream client...");
        running.set(false);
        streamThread.interrupt();

        try {
            streamThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
