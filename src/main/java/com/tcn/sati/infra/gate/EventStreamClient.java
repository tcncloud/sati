package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v2.*;
import com.tcn.sati.infra.backend.TenantBackendClient;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams events from Gate using the EventStream bidirectional API with ACKs.
 *
 * Pattern (matches old sati's GateClientEventStream):
 * 1. Open one long-lived bidirectional stream
 * 2. Send request with event_count + any carry-over ACK IDs
 * 3. Server responds with events
 * 4. Client processes events, buffers ACK IDs
 * 5. Client sends next request on SAME stream with new ACKs
 * 6. Repeat until server closes (~5 min timeout)
 * 7. Reconnect — pending ACKs carry over to next stream
 */
public class EventStreamClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EventStreamClient.class);

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final long RECONNECT_BASE_MS = 5_000;
    private static final long RECONNECT_MAX_MS = 60_000;
    private static final double BACKOFF_JITTER = 0.2;

    // How long to wait for stream activity before considering it hung
    private static final long HUNG_THRESHOLD_SECONDS = 45;
    // Max time a single stream session lives (server forces ~5 min)
    private static final long STREAM_TIMEOUT_MINUTES = 5;
    // Pause when server returns empty batch to avoid hot-looping
    private static final long EMPTY_BATCH_PAUSE_MS = 10_000;

    private final GateClient gateClient;
    private final TenantBackendClient backendClient;
    private final int batchSize;
    private final Thread streamThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong eventsFailed = new AtomicLong(0);
    private final AtomicLong reconnectAttempts = new AtomicLong(0);

    // Pending ACKs — survive stream reconnections.
    // Added after successful processing, removed after successful send.
    private final List<String> pendingAcks = new ArrayList<>();

    public EventStreamClient(GateClient gateClient, TenantBackendClient backendClient) {
        this(gateClient, backendClient, DEFAULT_BATCH_SIZE);
    }

    public EventStreamClient(GateClient gateClient, TenantBackendClient backendClient, int batchSize) {
        this.gateClient = gateClient;
        this.backendClient = backendClient;
        this.batchSize = batchSize;
        this.streamThread = new Thread(this::streamLoop, "event-stream");
        this.streamThread.setDaemon(true);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting event stream client (batch size: {})", batchSize);
            streamThread.start();
        }
    }

    // ========== Stream Lifecycle ==========

    private void streamLoop() {
        long consecutiveFailures = 0;

        while (running.get()) {
            try {
                reconnectAttempts.incrementAndGet();

                // Backoff with jitter before reconnect
                long backoffMs = computeBackoff(consecutiveFailures);
                if (backoffMs > 0) {
                    log.debug("Waiting {}ms before reconnect (failures: {})",
                            backoffMs, consecutiveFailures);
                    sleep(backoffMs);
                }

                log.info("Connecting to event stream (attempt #{})...", reconnectAttempts.get());
                gateClient.resetConnectBackoff();
                runStream();

                // Normal completion (server closed after ~5 min) — reset
                consecutiveFailures = 0;

            } catch (InterruptedException e) {
                log.info("Event stream interrupted");
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                connected.set(false);
                consecutiveFailures++;
                log.warn("Event stream error (failure #{}): {}",
                        consecutiveFailures, e.getMessage());
            }
        }

        connected.set(false);
        log.info("Event stream stopped (processed: {}, failed: {})",
                eventsProcessed.get(), eventsFailed.get());
    }

    /**
     * Run a single bidirectional stream session.
     * Sends initial request, then loops: receive events → process → send next request with ACKs.
     * Returns when the server closes the stream (~5 min) or an error occurs.
     */
    private void runStream() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var errorRef = new AtomicReference<Throwable>();
        var requestObserverRef = new AtomicReference<StreamObserver<EventStreamRequest>>();
        var lastMessageTime = new AtomicReference<Long>(System.currentTimeMillis());

        var responseObserver = new StreamObserver<EventStreamResponse>() {
            @Override
            public void onNext(EventStreamResponse response) {
                lastMessageTime.set(System.currentTimeMillis());

                if (connected.compareAndSet(false, true)) {
                    log.info("✅ Connected to event stream");
                }

                if (response.getEventsCount() == 0) {
                    log.debug("Received empty event batch, requesting next");
                    // Brief pause to avoid hot-looping when no events
                    sleep(EMPTY_BATCH_PAUSE_MS);
                    sendNextRequest(requestObserverRef.get());
                    return;
                }

                log.debug("Received {} events", response.getEventsCount());
                processBatch(response.getEventsList());
                sendNextRequest(requestObserverRef.get());
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Event stream error: {}", t.getMessage());
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("Event stream completed by server");
                latch.countDown();
            }
        };

        // Open bidirectional stream
        var requestObserver = GateServiceGrpc.newStub(gateClient.getChannel())
                .eventStream(responseObserver);
        requestObserverRef.set(requestObserver);

        // Send initial request — include any pending ACKs from a previous broken stream
        List<String> carryOverAcks;
        synchronized (pendingAcks) {
            carryOverAcks = new ArrayList<>(pendingAcks);
        }
        if (!carryOverAcks.isEmpty()) {
            log.info("Sending {} pending ACKs from previous stream session", carryOverAcks.size());
        }

        requestObserver.onNext(EventStreamRequest.newBuilder()
                .setEventCount(batchSize)
                .addAllAckEventIds(carryOverAcks)
                .build());

        // Only clear after successful send
        if (!carryOverAcks.isEmpty()) {
            synchronized (pendingAcks) {
                pendingAcks.removeAll(carryOverAcks);
            }
        }

        // Wait for stream to complete, checking for hung connection periodically
        awaitStreamWithHungDetection(latch, lastMessageTime);
        connected.set(false);

        // Clean up observer ref
        requestObserverRef.set(null);

        // Propagate error if any
        var error = errorRef.get();
        if (error instanceof StatusRuntimeException sre) {
            throw sre;
        } else if (error != null) {
            throw new RuntimeException("Stream error", error);
        }
    }

    // ========== Request & ACK Management ==========

    /**
     * Send the next request on the stream, draining pendingAcks into the request.
     * If the send fails, ACKs remain in pendingAcks for the next attempt.
     */
    private void sendNextRequest(StreamObserver<EventStreamRequest> observer) {
        if (observer == null) {
            log.warn("Cannot send next request - no active observer");
            return;
        }

        List<String> acksToSend;
        synchronized (pendingAcks) {
            acksToSend = new ArrayList<>(pendingAcks);
        }

        try {
            observer.onNext(EventStreamRequest.newBuilder()
                    .setEventCount(batchSize)
                    .addAllAckEventIds(acksToSend)
                    .build());

            // Only clear after successful send
            if (!acksToSend.isEmpty()) {
                synchronized (pendingAcks) {
                    pendingAcks.removeAll(acksToSend);
                }
                log.debug("Sent ACK for {} events, requesting next batch", acksToSend.size());
            }
        } catch (Exception e) {
            log.error("Failed to send next event stream request (keeping {} pending ACKs): {}",
                    acksToSend.size(), e.getMessage());
        }
    }

    // ========== Event Processing ==========

    /**
     * Process a batch of events, adding successful ACK IDs to the pending buffer.
     */
    private void processBatch(List<Event> events) {
        for (Event event : events) {
            String eventId = getEventId(event);
            if (processEvent(event)) {
                synchronized (pendingAcks) {
                    pendingAcks.add(eventId);
                }
                eventsProcessed.incrementAndGet();
            } else {
                eventsFailed.incrementAndGet();
                log.warn("Event {} NOT acknowledged - will be redelivered", eventId);
            }
        }
    }

    /**
     * Process a single event. Returns true if successfully processed (should ACK).
     */
    private boolean processEvent(Event event) {
        if (!backendClient.isConnected()) {
            log.warn("Backend not connected, unable to process event");
            return false;
        }

        try {
            switch (event.getEntityCase()) {
                case AGENT_CALL:
                    var ac = convertAgentCall(event.getAgentCall());
                    String acRpc = backendClient.handleAgentCall(ac);
                    if (acRpc != null && !acRpc.isBlank()) {
                        sendRpcResponse(ac.callSid, acRpc);
                    }
                    break;
                case TELEPHONY_RESULT:
                    var tr = convertTelephonyResult(event.getTelephonyResult());
                    String rpc = backendClient.handleTelephonyResult(tr);
                    if (rpc != null && !rpc.isBlank()) {
                        sendRpcResponse(tr.callSid, rpc);
                    }
                    break;
                case AGENT_RESPONSE:
                    backendClient.handleAgentResponse(convertAgentResponse(event.getAgentResponse()));
                    break;
                case TASK:
                    backendClient.handleTask(convertTask(event.getTask()));
                    break;
                case TRANSFER_INSTANCE:
                    backendClient.handleTransferInstance(convertTransferInstance(event.getTransferInstance()));
                    break;
                case CALL_RECORDING:
                    backendClient.handleCallRecording(convertCallRecording(event.getCallRecording()));
                    break;
                default:
                    log.warn("Unknown event type: {}", event.getEntityCase());
                    return true; // ACK unknown events to not block queue
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to process event: {}", e.getMessage());
            return false;
        }
    }

    // ========== Hung Detection ==========

    /**
     * Wait for stream latch, checking for hung connections every HUNG_THRESHOLD_SECONDS.
     * Matches old sati's awaitStreamWithHungDetection pattern.
     */
    private void awaitStreamWithHungDetection(CountDownLatch latch,
            AtomicReference<Long> lastMessageTime) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long maxDurationMs = TimeUnit.MINUTES.toMillis(STREAM_TIMEOUT_MINUTES);

        while ((System.currentTimeMillis() - startTime) < maxDurationMs) {
            if (latch.await(HUNG_THRESHOLD_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            // Check if connection is hung
            long lastMsg = lastMessageTime.get();
            long silenceMs = System.currentTimeMillis() - lastMsg;
            if (silenceMs > TimeUnit.SECONDS.toMillis(HUNG_THRESHOLD_SECONDS)) {
                throw new RuntimeException(
                        "Event stream appears hung - no messages for " + (silenceMs / 1000) + "s");
            }
        }
    }

    // ========== Backoff ==========

    /**
     * Compute backoff with jitter. Prevents thundering herd.
     */
    private long computeBackoff(long failures) {
        if (failures <= 0) return 0;
        long delayMs = RECONNECT_BASE_MS * (1L << Math.min(failures - 1, 10));
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
        return Math.min((long) (delayMs * jitter), RECONNECT_MAX_MS);
    }

    // ========== RPC Callback ==========

    /**
     * Send an AddAgentCallResponse back to Gate when a stored procedure returns an RPC value.
     * This matches legacy finvi behavior where TelephonyResult SPs could trigger Gate callbacks.
     */
    private void sendRpcResponse(String callSid, String rpcValue) {
        try {
            log.info("Sending RPC response for callSid: {} key=RPC value={}", callSid, rpcValue);
            var request = build.buf.gen.tcnapi.exile.gate.v2.AddAgentCallResponseRequest.newBuilder()
                    .setKey("RPC")
                    .setValue(rpcValue)
                    .build();
            gateClient.addAgentCallResponse(request);
        } catch (Exception e) {
            log.error("Failed to send RPC response for callSid: {}", callSid, e);
        }
    }

    // ========== Convert proto to DTOs ==========

    private TenantBackendClient.AgentCall convertAgentCall(ExileAgentCall proto) {
        var dto = new TenantBackendClient.AgentCall();
        dto.agentCallSid = String.valueOf(proto.getAgentCallSid());
        dto.callSid = String.valueOf(proto.getCallSid());
        dto.callType = proto.getCallType();
        dto.userId = proto.getUserId();
        dto.partnerAgentId = proto.getPartnerAgentId();
        dto.orgId = proto.getOrgId();
        dto.internalKey = proto.getInternalKey();
        dto.talkDuration = proto.getTalkDuration();
        dto.callWaitDuration = proto.getCallWaitDuration();
        dto.wrapUpDuration = proto.getWrapUpDuration();
        dto.pauseDuration = proto.getPauseDuration();
        dto.transferDuration = proto.getTransferDuration();
        dto.manualDuration = proto.getManualDuration();
        dto.previewDuration = proto.getPreviewDuration();
        dto.holdDuration = proto.getHoldDuration();
        dto.agentWaitDuration = proto.getAgentWaitDuration();
        dto.suspendedDuration = proto.getSuspendedDuration();
        dto.externalTransferDuration = proto.getExternalTransferDuration();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        return dto;
    }

    private TenantBackendClient.TelephonyResult convertTelephonyResult(ExileTelephonyResult proto) {
        var dto = new TenantBackendClient.TelephonyResult();
        dto.callSid = String.valueOf(proto.getCallSid());
        dto.callType = proto.getCallType();
        dto.status = proto.getStatus().name();
        dto.result = proto.getResult().name();
        dto.callerId = proto.getCallerId();
        dto.phoneNumber = proto.getPhoneNumber();
        dto.recordId = proto.getRecordId();
        dto.poolId = proto.getPoolId();
        dto.deliveryLength = proto.getDeliveryLength();
        dto.linkbackLength = proto.getLinkbackLength();
        dto.orgId = proto.getOrgId();
        dto.clientSid = proto.getClientSid();
        dto.internalKey = proto.getInternalKey();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        dto.startTime = proto.hasStartTime() ? toIso(proto.getStartTime()) : null;
        dto.endTime = proto.hasEndTime() ? toIso(proto.getEndTime()) : null;
        dto.taskWaitingUntil = proto.hasTaskWaitingUntil() ? toIso(proto.getTaskWaitingUntil()) : null;
        dto.oldCallSid = proto.getOldCallSid();
        dto.oldCallType = proto.getOldCallType();

        // Fallback: extract pool_id/record_id from task_data if missing on root fields
        boolean needsPoolId = dto.poolId == null || dto.poolId.isBlank();
        boolean needsRecordId = dto.recordId == null || dto.recordId.isBlank();
        if (needsPoolId || needsRecordId) {
            var keys = proto.getTaskDataKeysList();
            var values = proto.getTaskDataValuesList();
            for (int i = 0; i < keys.size() && i < values.size(); i++) {
                String key = keys.get(i).getStringValue();
                String value = values.get(i).getStringValue();
                if (value == null || value.isBlank()) continue;
                if (needsPoolId && ("poolid".equalsIgnoreCase(key) || "pool_id".equalsIgnoreCase(key))) {
                    dto.poolId = value;
                    needsPoolId = false;
                }
                if (needsRecordId && ("recordid".equalsIgnoreCase(key) || "record_id".equalsIgnoreCase(key))) {
                    dto.recordId = value;
                    needsRecordId = false;
                }
            }
        }

        return dto;
    }

    private TenantBackendClient.AgentResponse convertAgentResponse(ExileAgentResponse proto) {
        var dto = new TenantBackendClient.AgentResponse();
        dto.agentCallResponseSid = String.valueOf(proto.getAgentCallResponseSid());
        dto.callSid = String.valueOf(proto.getCallSid());
        dto.callType = proto.getCallType();
        dto.responseKey = proto.getResponseKey();
        dto.responseValue = proto.getResponseValue();
        dto.userId = proto.getUserId();
        dto.agentSid = String.valueOf(proto.getAgentSid());
        dto.partnerAgentId = proto.getPartnerAgentId();
        dto.orgId = proto.getOrgId();
        dto.clientSid = proto.getClientSid();
        dto.internalKey = proto.getInternalKey();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        return dto;
    }

    private TenantBackendClient.ExileTask convertTask(ExileTask proto) {
        var dto = new TenantBackendClient.ExileTask();
        dto.taskSid = String.valueOf(proto.getTaskSid());
        dto.taskGroupSid = String.valueOf(proto.getTaskGroupSid());
        dto.poolId = proto.getPoolId();
        dto.recordId = proto.getRecordId();
        dto.status = proto.getStatus().name();
        dto.attempts = proto.getAttempts();
        dto.clientSid = proto.getClientSid();
        dto.orgId = proto.getOrgId();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        return dto;
    }

    private TenantBackendClient.TransferInstance convertTransferInstance(ExileTransferInstance proto) {
        var dto = new TenantBackendClient.TransferInstance();
        dto.transferInstanceId = proto.getTransferInstanceId();
        dto.clientSid = proto.getClientSid();
        dto.orgId = proto.getOrgId();
        dto.transferResult = proto.getTransferResult().name();
        dto.transferType = proto.getTransferType().name();
        if (proto.hasSource() && proto.getSource().hasCall()) {
            var src = proto.getSource().getCall();
            dto.sourceCallSid = String.valueOf(src.getCallSid());
            dto.sourceCallType = src.getCallType();
            dto.sourceUserId = src.getUserId();
            dto.sourcePartnerAgentId = src.getPartnerAgentId();
        }
        dto.durationMicroseconds = proto.getDurationMicroseconds();
        dto.externalDurationMicroseconds = proto.getExternalDurationMicroseconds();
        dto.pendingDurationMicroseconds = proto.getPendingDurationMicroseconds();
        dto.startAsPending = proto.getStartAsPending();
        dto.startedAsConference = proto.getStartedAsConference();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        dto.transferPendingStartTime = proto.hasTransferPendingStartTime() ? toIso(proto.getTransferPendingStartTime()) : null;
        dto.transferStartTime = proto.hasTransferStartTime() ? toIso(proto.getTransferStartTime()) : null;
        dto.transferEndTime = proto.hasTransferEndTime() ? toIso(proto.getTransferEndTime()) : null;
        dto.transferExternalEndTime = proto.hasTransferExternalEndTime() ? toIso(proto.getTransferExternalEndTime()) : null;
        return dto;
    }

    private TenantBackendClient.CallRecording convertCallRecording(ExileCallRecording proto) {
        var dto = new TenantBackendClient.CallRecording();
        dto.recordingId = proto.getRecordingId();
        dto.callSid = String.valueOf(proto.getCallSid());
        dto.callType = proto.getCallType();
        dto.recordingType = proto.getRecordingType().name();
        dto.orgId = proto.getOrgId();
        dto.durationSeconds = proto.hasDuration() ? proto.getDuration().getSeconds() : 0;
        dto.startTime = proto.hasStartTime() ? toIso(proto.getStartTime()) : null;
        return dto;
    }

    private static String toIso(com.google.protobuf.Timestamp ts) {
        return java.time.Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString();
    }

    /**
     * Extract the server-assigned entity ID for ACK purposes.
     * The server sets exile_entity_id on each Event, which may be a composite
     * key (e.g. "call_type,call_sid"). We must echo this exact value back.
     */
    private String getEventId(Event event) {
        String entityId = event.getExileEntityId();
        if (entityId == null || entityId.isEmpty()) {
            log.warn("Event has no exile_entity_id, cannot ACK. Event type: {}",
                    event.getEntityCase());
            return "unknown";
        }
        return entityId;
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

    public boolean isConnected() {
        return connected.get();
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

        synchronized (pendingAcks) {
            if (!pendingAcks.isEmpty()) {
                log.warn("Shutting down with {} un-sent ACKs (these events will be redelivered)",
                        pendingAcks.size());
            }
        }

        try {
            streamThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
