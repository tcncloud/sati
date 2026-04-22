package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.executor.PriorityExecutor;
import com.tcn.sati.infra.logging.MemoryLogAppender;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements the v3 WorkStream protocol over a single bidirectional gRPC stream.
 *
 * Replaces the v2 triple of EventStreamClient + JobQueueClient + JobProcessor.
 *
 * Protocol:
 *   Register → Registered → Pull(MAX_VALUE)
 *   WorkItem (JOB)   → dispatch → Result
 *   WorkItem (EVENT) → dispatch → Ack
 *   Heartbeat        → echo Heartbeat
 *   LeaseExpiring    → ExtendLease
 */
public class WorkStreamClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WorkStreamClient.class);

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("com.tcn.sati", "1.0.0");

    private static final long BACKOFF_BASE_MS = 500;
    private static final long BACKOFF_MAX_MS = 10_000;

    public enum Phase { IDLE, CONNECTING, REGISTERING, ACTIVE, RECONNECTING, CLOSED, DRAINING }

    private final GateClient gateClient;
    private final TenantBackendClient backendClient;
    private final String clientName;
    private final String clientVersion;
    private final int maxConcurrency;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<StreamObserver<WorkRequest>> requestObserver = new AtomicReference<>();
    private final AtomicInteger inflight = new AtomicInteger(0);
    private final AtomicInteger outstandingCredits = new AtomicInteger(0);
    private final ExecutorService workerPool = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicLong completedTotal = new AtomicLong(0);
    private final AtomicLong failedTotal = new AtomicLong(0);
    private final AtomicLong reconnectAttempts = new AtomicLong(0);

    private static final java.time.Duration DEFAULT_SHUTDOWN_DRAIN_TIMEOUT = java.time.Duration.ofSeconds(30);
    private volatile java.time.Duration shutdownDrainTimeout = DEFAULT_SHUTDOWN_DRAIN_TIMEOUT;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    // Maps work_id → SpanContext for async response correlation.
    private final ConcurrentHashMap<String, SpanContext> workSpanContexts = new ConcurrentHashMap<>();

    private volatile Phase phase = Phase.IDLE;
    private volatile Thread streamThread;
    private volatile boolean lastDisconnectGraceful;
    private volatile Instant lastDisconnect;
    private volatile long consecutiveFailures = 0;

    // Metric callbacks — wired in by TenantContext after first config.
    private volatile java.util.function.DoubleConsumer durationRecorder;
    private volatile java.util.function.DoubleConsumer reconnectRecorder;
    private volatile MethodRecorder methodRecorder;
    private volatile PriorityExecutor priorityExecutor;
    private volatile DiagnosticsSupplier diagnosticsSupplier;
    private volatile com.tcn.sati.infra.adaptive.AdaptiveCapacity adaptive;

    @FunctionalInterface
    public interface MethodRecorder {
        void record(String method, double durationSeconds, boolean success);
    }

    @FunctionalInterface
    public interface DiagnosticsSupplier {
        Map<String, Object> get();
    }

    public WorkStreamClient(GateClient gateClient, TenantBackendClient backendClient,
                            String clientName, String clientVersion, int maxConcurrency) {
        this.gateClient = gateClient;
        this.backendClient = backendClient;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
        this.maxConcurrency = maxConcurrency;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("WorkStreamClient already started");
        }
        phase = Phase.CONNECTING;
        streamThread = Thread.ofPlatform().name("work-stream").daemon(true).start(this::reconnectLoop);
    }

    public Phase getPhase() { return phase; }

    public void setDurationRecorder(java.util.function.DoubleConsumer recorder) {
        this.durationRecorder = recorder;
    }

    public void setReconnectRecorder(java.util.function.DoubleConsumer recorder) {
        this.reconnectRecorder = recorder;
    }

    public void setMethodRecorder(MethodRecorder recorder) {
        this.methodRecorder = recorder;
    }

    public void setPriorityExecutor(PriorityExecutor executor) {
        this.priorityExecutor = executor;
    }

    public PriorityExecutor getPriorityExecutor() {
        return priorityExecutor;
    }

    public void setDiagnosticsSupplier(DiagnosticsSupplier supplier) {
        this.diagnosticsSupplier = supplier;
    }

    public void setAdaptiveCapacity(com.tcn.sati.infra.adaptive.AdaptiveCapacity adaptive) {
        this.adaptive = adaptive;
    }

    public void setShutdownDrainTimeout(java.time.Duration timeout) {
        this.shutdownDrainTimeout = timeout;
    }

    // ========== Reconnect loop ==========

    private void reconnectLoop() {
        while (running.get()) {
            if (draining.get()) break;
            try {
                long delayMs = computeBackoff(consecutiveFailures);
                if (delayMs > 0) {
                    phase = Phase.RECONNECTING;
                    log.info("Reconnecting in {}ms (attempt #{})", delayMs, reconnectAttempts.get() + 1);
                    Thread.sleep(delayMs);
                }
                phase = Phase.CONNECTING;
                long attempt = reconnectAttempts.incrementAndGet();
                log.info("Connecting to {}:{} (attempt #{})",
                        gateClient.getConfig().apiHostname(), gateClient.getConfig().apiPort(), attempt);
                runStream();
                if (lastDisconnectGraceful) {
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                }
                lastDisconnectGraceful = false;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                consecutiveFailures++;
                lastDisconnect = Instant.now();
                log.warn("Stream error ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }
        phase = Phase.CLOSED;
        log.info("Work stream loop exited");
    }

    private void runStream() throws InterruptedException {
        var latch = new CountDownLatch(1);

        var observer = WorkerServiceGrpc.newStub(gateClient.getChannel())
                .workStream(new StreamObserver<>() {
                    @Override
                    public void onNext(WorkResponse response) {
                        handleResponse(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        String msg = t.getMessage();
                        lastDisconnectGraceful = msg != null
                                && msg.contains("RST_STREAM") && msg.contains("NO_ERROR");
                        lastDisconnect = Instant.now();
                        log.warn("Stream error ({}): {}", t.getClass().getSimpleName(), t.getMessage());
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        lastDisconnectGraceful = true;
                        lastDisconnect = Instant.now();
                        log.info("Stream completed by server");
                        latch.countDown();
                    }
                });

        requestObserver.set(observer);

        // Register with server.
        phase = Phase.REGISTERING;
        send(WorkRequest.newBuilder()
                .setRegister(Register.newBuilder()
                        .setClientName(clientName)
                        .setClientVersion(clientVersion)
                        .addAllCapabilities(allCapabilities()))
                .build());

        latch.await();
        requestObserver.set(null);
        inflight.set(0);
        outstandingCredits.set(0);
    }

    /**
     * How many credits we should have outstanding against Gate right now.
     * Capped by adaptive limit (or maxConcurrency fallback) and backendClient.availableCapacity().
     */
    private int capacityTarget() {
        if (draining.get()) return 0;
        int cap;
        try {
            cap = backendClient.availableCapacity();
        } catch (Throwable t) {
            log.warn("availableCapacity() threw {}, falling back to limit", t.toString());
            cap = Integer.MAX_VALUE;
        }
        if (cap < 0) cap = 0;
        var a = adaptive;
        int limit = (a != null) ? a.getAsInt() : (maxConcurrency > 0 ? maxConcurrency : Integer.MAX_VALUE);
        return Math.min(limit, cap);
    }

    /**
     * Send exactly enough credits to bring outstanding credits up to the target.
     * No-op during drain or when already at/above target.
     */
    private void refillToTarget() {
        if (draining.get()) return;
        int delta = capacityTarget() - outstandingCredits.get();
        if (delta > 0) {
            pull(delta);
            outstandingCredits.addAndGet(delta);
        }
    }

    private void handleResponse(WorkResponse response) {
        switch (response.getPayloadCase()) {
            case REGISTERED -> {
                var reg = response.getRegistered();
                var disconnectTime = lastDisconnect;
                if (disconnectTime != null) {
                    double reconnectSec = java.time.Duration.between(disconnectTime, Instant.now()).toMillis() / 1000.0;
                    var rr = reconnectRecorder;
                    if (rr != null) rr.accept(reconnectSec);
                    log.info("Reconnected in {}ms", (long)(reconnectSec * 1000));
                    lastDisconnect = null;
                }
                phase = Phase.ACTIVE;
                log.info("Registered as {} (heartbeat={}s, lease={}s, max_inflight={})",
                        reg.getClientId(),
                        reg.getHeartbeatInterval().getSeconds(),
                        reg.getDefaultLease().getSeconds(),
                        reg.getMaxInflight());
                outstandingCredits.set(0);
                refillToTarget();
            }
            case WORK_ITEM -> {
                inflight.incrementAndGet();
                outstandingCredits.decrementAndGet();
                workerPool.submit(() -> processWorkItem(response.getWorkItem()));
            }
            case RESULT_ACCEPTED -> {
                String workId = response.getResultAccepted().getWorkId();
                workSpanContexts.remove(workId);
            }
            case LEASE_EXPIRING -> {
                var w = response.getLeaseExpiring();
                log.debug("Lease expiring for {}, {}s remaining", w.getWorkId(), w.getRemaining().getSeconds());
                send(WorkRequest.newBuilder()
                        .setExtendLease(ExtendLease.newBuilder()
                                .setWorkId(w.getWorkId())
                                .setExtension(com.google.protobuf.Duration.newBuilder().setSeconds(300)))
                        .build());
            }
            case HEARTBEAT -> send(WorkRequest.newBuilder()
                    .setHeartbeat(Heartbeat.newBuilder()
                            .setClientTime(com.google.protobuf.Timestamp.newBuilder()
                                    .setSeconds(Instant.now().getEpochSecond())))
                    .build());
            case ERROR -> {
                var err = response.getError();
                log.warn("Stream error for {}: {} - {}", err.getWorkId(), err.getCode(), err.getMessage());
            }
            default -> {}
        }
    }

    // ========== Work item dispatch ==========

    private void processWorkItem(WorkItem item) {
        long startNanos = System.nanoTime();
        String workId = item.getWorkId();
        String category = item.getCategory() == WorkCategory.WORK_CATEGORY_JOB ? "job" : "event";
        boolean isJob = item.getCategory() == WorkCategory.WORK_CATEGORY_JOB;
        boolean jobSuccess = false;

        var spanBuilder = tracer.spanBuilder("exile.work." + category)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("exile.work_id", workId)
                .setAttribute("exile.work_category", category);

        var parentCtx = parseTraceParent(item.getTraceParent());
        if (parentCtx != null) {
            spanBuilder.setParent(Context.current().with(Span.wrap(parentCtx)));
        }

        Span span = spanBuilder.startSpan();
        workSpanContexts.put(workId, span.getSpanContext());

        try (Scope ignored = span.makeCurrent()) {
            MDC.put("traceId", span.getSpanContext().getTraceId());
            MDC.put("spanId", span.getSpanContext().getSpanId());

            var pe = priorityExecutor;
            if (pe != null) {
                var priority = classifyPriority(item);
                var type = item.getTaskCase().name().toLowerCase();
                pe.submit(priority, type, () -> {
                    if (item.getCategory() == WorkCategory.WORK_CATEGORY_JOB) {
                        var result = dispatchJob(item);
                        send(WorkRequest.newBuilder().setResult(result).build());
                    } else {
                        dispatchEvent(item);
                        send(WorkRequest.newBuilder().setAck(Ack.newBuilder().addWorkIds(workId)).build());
                    }
                });
            } else {
                if (item.getCategory() == WorkCategory.WORK_CATEGORY_JOB) {
                    var result = dispatchJob(item);
                    send(WorkRequest.newBuilder().setResult(result).build());
                } else {
                    dispatchEvent(item);
                    send(WorkRequest.newBuilder().setAck(Ack.newBuilder().addWorkIds(workId)).build());
                }
            }
            completedTotal.incrementAndGet();
            jobSuccess = true;

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            failedTotal.incrementAndGet();
            log.warn("Work item {} failed: {}", workId, e.getMessage());
            if (item.getCategory() == WorkCategory.WORK_CATEGORY_JOB) {
                send(WorkRequest.newBuilder()
                        .setResult(Result.newBuilder()
                                .setWorkId(workId)
                                .setFinal(true)
                                .setError(ErrorResult.newBuilder().setMessage(e.getMessage())))
                        .build());
            } else {
                send(WorkRequest.newBuilder()
                        .setNack(Nack.newBuilder().setWorkId(workId).setReason(e.getMessage()))
                        .build());
            }
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
            span.end();
            if (item.getCategory() != WorkCategory.WORK_CATEGORY_JOB) {
                workSpanContexts.remove(workId);
            }
            var dr = durationRecorder;
            if (dr != null) dr.accept((System.nanoTime() - startNanos) / 1_000_000_000.0);
            inflight.decrementAndGet();
            // Feed job completion latency into the adaptive controller.
            var a = adaptive;
            if (a != null && isJob) {
                a.recordJobCompletion(System.nanoTime() - startNanos, jobSuccess);
            }
            // Refill credits in batch up to target (drain-safe).
            refillToTarget();
        }
    }

    // ========== Job dispatch ==========

    private Result.Builder dispatchJob(WorkItem item) throws Exception {
        var b = Result.newBuilder().setWorkId(item.getWorkId()).setFinal(true);
        String methodName = item.getTaskCase().name().toLowerCase();
        long methodStart = System.nanoTime();
        boolean methodSuccess = false;
        try {
        switch (item.getTaskCase()) {
            case LIST_POOLS -> {
                var pools = backendClient.listPools();
                var rb = ListPoolsResult.newBuilder();
                for (var p : pools) {
                    rb.addPools(Pool.newBuilder()
                            .setPoolId(p.id != null ? p.id : "")
                            .setDescription(p.name != null ? p.name : "")
                            .setRecordCount(p.recordCount)
                            .setStatus(mapPoolStatus(p.status))
                            .build());
                }
                b.setListPools(rb);
            }
            case GET_POOL_STATUS -> {
                String poolId = item.getGetPoolStatus().getPoolId();
                var ps = backendClient.getPoolStatus(poolId);
                b.setGetPoolStatus(GetPoolStatusResult.newBuilder()
                        .setPool(Pool.newBuilder()
                                .setPoolId(ps.poolId != null ? ps.poolId : poolId)
                                .setDescription(ps.description != null ? ps.description : "")
                                .setRecordCount(ps.totalRecords)
                                .setStatus(mapPoolStatus(ps.status))
                                .build()));
            }
            case GET_POOL_RECORDS -> {
                var task = item.getGetPoolRecords();
                // pageToken is a string page number; default to 0 if absent or non-numeric.
                int page = 0;
                if (!task.getPageToken().isBlank()) {
                    try { page = Integer.parseInt(task.getPageToken()); } catch (NumberFormatException ignored) {}
                }
                var records = backendClient.getPoolRecords(task.getPoolId(), page);
                var rb = GetPoolRecordsResult.newBuilder();
                for (var r : records) {
                    rb.addRecords(build.buf.gen.tcnapi.exile.gate.v3.Record.newBuilder()
                            .setPoolId(r.poolId != null ? r.poolId : "")
                            .setRecordId(r.recordId != null ? r.recordId : "")
                            .build());
                }
                // Signal next page only if we got a full page.
                int pageSize = task.getPageSize() > 0 ? task.getPageSize() : 100;
                if (records != null && records.size() >= pageSize) {
                    rb.setNextPageToken(String.valueOf(page + 1));
                }
                b.setGetPoolRecords(rb);
            }
            case SEARCH_RECORDS -> {
                var task = item.getSearchRecords();
                // Adapt v3 Filter list → SearchRecordsRequest
                String lookupType = "";
                String lookupValue = "";
                Map<String, String> filterMap = new HashMap<>();
                for (var f : task.getFiltersList()) {
                    if (lookupType.isEmpty()) {
                        lookupType = f.getField();
                        lookupValue = f.getValue();
                    } else {
                        filterMap.put(f.getField(), f.getValue());
                    }
                }
                var req = new TenantBackendClient.SearchRecordsRequest(lookupType, lookupValue, filterMap);
                var results = backendClient.searchRecords(req);
                var rb = SearchRecordsResult.newBuilder();
                for (var r : results) {
                    var record = build.buf.gen.tcnapi.exile.gate.v3.Record.newBuilder()
                            .setPoolId(r.poolId != null ? r.poolId : "")
                            .setRecordId(r.recordId != null ? r.recordId : "");
                    if (r.fields != null && !r.fields.isEmpty()) {
                        var structBuilder = com.google.protobuf.Struct.newBuilder();
                        for (var entry : r.fields.entrySet()) {
                            var val = entry.getValue();
                            com.google.protobuf.Value v;
                            if (val == null) {
                                v = com.google.protobuf.Value.newBuilder()
                                        .setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
                            } else if (val instanceof Number n) {
                                v = com.google.protobuf.Value.newBuilder().setNumberValue(n.doubleValue()).build();
                            } else {
                                v = com.google.protobuf.Value.newBuilder().setStringValue(val.toString()).build();
                            }
                            structBuilder.putFields(entry.getKey(), v);
                        }
                        record.setPayload(structBuilder.build());
                    }
                    rb.addRecords(record.build());
                }
                b.setSearchRecords(rb);
            }
            case GET_RECORD_FIELDS -> {
                var task = item.getGetRecordFields();
                var req = new TenantBackendClient.ReadFieldsRequest(
                        task.getRecordId(), task.getPoolId(), task.getFieldNamesList(), null);
                var fields = backendClient.readFields(req);
                var rb = GetRecordFieldsResult.newBuilder();
                for (var f : fields) {
                    rb.addFields(Field.newBuilder()
                            .setFieldName(f.fieldName != null ? f.fieldName : "")
                            .setFieldValue(f.fieldValue != null ? f.fieldValue : "")
                            .setPoolId(f.poolId != null ? f.poolId : "")
                            .setRecordId(f.recordId != null ? f.recordId : "")
                            .build());
                }
                b.setGetRecordFields(rb);
            }
            case SET_RECORD_FIELDS -> {
                var task = item.getSetRecordFields();
                Map<String, String> fieldsMap = new HashMap<>();
                for (var f : task.getFieldsList()) {
                    fieldsMap.put(f.getFieldName(), f.getFieldValue());
                }
                var req = new TenantBackendClient.WriteFieldsRequest(task.getRecordId(), fieldsMap, null);
                backendClient.writeFields(req);
                b.setSetRecordFields(SetRecordFieldsResult.newBuilder().setSuccess(true));
            }
            case CREATE_PAYMENT -> {
                var task = item.getCreatePayment();
                Map<String, String> paymentData = structToStringMap(task.getPaymentData());
                var req = new TenantBackendClient.CreatePaymentRequest(
                        task.getRecordId(),
                        paymentData.getOrDefault("payment_id", ""),
                        paymentData.getOrDefault("payment_type", ""),
                        paymentData.getOrDefault("payment_amount", ""),
                        Long.parseLong(paymentData.getOrDefault("payment_date", "0")));
                backendClient.createPayment(req);
                b.setCreatePayment(CreatePaymentResult.newBuilder().setSuccess(true));
            }
            case POP_ACCOUNT -> {
                var task = item.getPopAccount();
                var req = new TenantBackendClient.PopAccountRequest(
                        task.getRecordId(), "", "", "", "", "");
                backendClient.popAccount(req);
                b.setPopAccount(PopAccountResult.newBuilder()
                        .setRecord(build.buf.gen.tcnapi.exile.gate.v3.Record.newBuilder()
                                .setPoolId(task.getPoolId())
                                .setRecordId(task.getRecordId())));
            }
            case EXECUTE_LOGIC -> {
                var task = item.getExecuteLogic();
                Map<String, String> params = structToStringMap(task.getParameters());
                var req = new TenantBackendClient.ExecuteLogicRequest(
                        task.getLogicName(),
                        params.getOrDefault("params", ""));
                String output = backendClient.executeLogic(req);
                var outStruct = com.google.protobuf.Struct.newBuilder();
                if (output != null && !output.isBlank()) {
                    outStruct.putFields("result", com.google.protobuf.Value.newBuilder()
                            .setStringValue(output).build());
                }
                b.setExecuteLogic(ExecuteLogicResult.newBuilder().setOutput(outStruct));
            }
            case INFO -> {
                String satiVersion = WorkStreamClient.class.getPackage().getImplementationVersion();
                if (satiVersion == null) satiVersion = "dev";
                b.setInfo(InfoResult.newBuilder()
                        .setAppName(clientName)
                        .setAppVersion(clientVersion)
                        .setMetadata(com.google.protobuf.Struct.newBuilder()
                                .putFields("satiVersion", com.google.protobuf.Value.newBuilder()
                                        .setStringValue(satiVersion).build())));
            }
            case SHUTDOWN -> {
                log.info("Shutdown requested: {}", item.getShutdown().getReason());
                b.setShutdown(ShutdownResult.getDefaultInstance());
            }
            case LOGGING -> {
                log.debug("Log payload: {}", item.getLogging().getPayload());
                b.setLogging(LoggingResult.getDefaultInstance());
            }
            case DIAGNOSTICS -> {
                Runtime rt = Runtime.getRuntime();
                String hostname;
                try { hostname = InetAddress.getLocalHost().getHostName(); }
                catch (Exception e) { hostname = "unknown"; }
                long heapUsed = rt.totalMemory() - rt.freeMemory();
                long heapMax = rt.maxMemory();
                String javaVersion = System.getProperty("java.version", "unknown");

                var customBuilder = com.google.protobuf.Struct.newBuilder()
                        .putFields("clientName", strVal(clientName))
                        .putFields("clientVersion", strVal(clientVersion));

                // Merge custom diagnostics from backend
                var ds = diagnosticsSupplier;
                if (ds != null) {
                    try {
                        Map<String, Object> custom = ds.get();
                        if (custom != null) {
                            for (var entry : custom.entrySet()) {
                                Object val = entry.getValue();
                                if (val instanceof Number n) {
                                    customBuilder.putFields(entry.getKey(), numVal(n.longValue()));
                                } else if (val instanceof Boolean bv) {
                                    customBuilder.putFields(entry.getKey(),
                                            com.google.protobuf.Value.newBuilder().setBoolValue(bv).build());
                                } else if (val != null) {
                                    customBuilder.putFields(entry.getKey(), strVal(String.valueOf(val)));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("DiagnosticsSupplier threw: {}", e.getMessage());
                    }
                }

                b.setDiagnostics(DiagnosticsResult.newBuilder()
                        .setSystemInfo(com.google.protobuf.Struct.newBuilder()
                                .putFields("hostname", strVal(hostname))
                                .putFields("os", strVal(System.getProperty("os.name", "unknown")))
                                .putFields("availableProcessors", numVal(rt.availableProcessors())))
                        .setRuntimeInfo(com.google.protobuf.Struct.newBuilder()
                                .putFields("javaVersion", strVal(javaVersion))
                                .putFields("heapUsedBytes", numVal(heapUsed))
                                .putFields("heapMaxBytes", numVal(heapMax)))
                        .setDatabaseInfo(com.google.protobuf.Struct.newBuilder()
                                .putFields("connected", com.google.protobuf.Value.newBuilder()
                                        .setBoolValue(backendClient.isConnected()).build()))
                        .setCustom(customBuilder));
            }
            case LIST_TENANT_LOGS -> {
                var task = item.getListTenantLogs();
                long startMs = task.hasStartTime() ? task.getStartTime().getSeconds() * 1000 : 0;
                long endMs = task.hasEndTime() ? task.getEndTime().getSeconds() * 1000 : System.currentTimeMillis();
                int pageSize = task.getPageSize() > 0 ? task.getPageSize() : 100;

                var entries = MemoryLogAppender.getEntriesInTimeRange(startMs, endMs);

                int offset = 0;
                if (!task.getPageToken().isBlank()) {
                    try { offset = Integer.parseInt(task.getPageToken()); } catch (NumberFormatException ignored) {}
                }

                var rb = ListTenantLogsResult.newBuilder();
                int end = Math.min(offset + pageSize, entries.size());
                for (int i = offset; i < end; i++) {
                    var entry = entries.get(i);
                    rb.addEntries(ListTenantLogsResult.LogEntry.newBuilder()
                            .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                                    .setSeconds(entry.timestampMs / 1000)
                                    .setNanos((int) ((entry.timestampMs % 1000) * 1_000_000)))
                            .setLevel(entry.level != null ? entry.level : "")
                            .setLogger(entry.loggerName != null ? entry.loggerName : "")
                            .setMessage(entry.rawMessage != null ? entry.rawMessage : entry.message));
                }
                rb.setNextPageToken(end < entries.size() ? String.valueOf(end) : "");
                b.setListTenantLogs(rb);
            }
            case SET_LOG_LEVEL -> {
                log.info("SetLogLevel: logger={}, level={}",
                        item.getSetLogLevel().getLoggerName(), item.getSetLogLevel().getLevel());
                b.setSetLogLevel(SetLogLevelResult.getDefaultInstance());
            }
            default -> throw new UnsupportedOperationException("Unknown job: " + item.getTaskCase());
        }
        methodSuccess = true;
        return b;
        } finally {
            var mr = methodRecorder;
            if (mr != null) {
                mr.record(methodName, (System.nanoTime() - methodStart) / 1_000_000_000.0, methodSuccess);
            }
        }
    }

    // ========== Event dispatch ==========

    private void dispatchEvent(WorkItem item) throws Exception {
        String methodName = item.getTaskCase().name().toLowerCase();
        long methodStart = System.nanoTime();
        boolean methodSuccess = false;
        try {
        switch (item.getTaskCase()) {
            case AGENT_CALL -> {
                var dto = convertAgentCall(item.getAgentCall());
                String rpc = backendClient.handleAgentCall(dto);
                if (rpc != null && !rpc.isBlank()) {
                    sendRpcResponse(String.valueOf(dto.callSid), item.getAgentCall().getCallType(), rpc);
                }
            }
            case TELEPHONY_RESULT -> {
                var dto = convertTelephonyResult(item.getTelephonyResult());
                String rpc = backendClient.handleTelephonyResult(dto);
                if (rpc != null && !rpc.isBlank()) {
                    sendRpcResponse(String.valueOf(dto.callSid), item.getTelephonyResult().getCallType(), rpc);
                }
            }
            case AGENT_RESPONSE -> backendClient.handleAgentResponse(convertAgentResponse(item.getAgentResponse()));
            case TRANSFER_INSTANCE -> backendClient.handleTransferInstance(convertTransferInstance(item.getTransferInstance()));
            case CALL_RECORDING -> backendClient.handleCallRecording(convertCallRecording(item.getCallRecording()));
            case EXILE_TASK -> backendClient.handleTask(convertExileTask(item.getExileTask()));
            default -> throw new UnsupportedOperationException("Unknown event: " + item.getTaskCase());
        }
        methodSuccess = true;
        } finally {
            var mr = methodRecorder;
            if (mr != null) {
                mr.record(methodName, (System.nanoTime() - methodStart) / 1_000_000_000.0, methodSuccess);
            }
        }
    }

    // ========== Priority classification ==========

    private static PriorityExecutor.Priority classifyPriority(WorkItem item) {
        return switch (item.getTaskCase()) {
            case LIST_POOLS, GET_POOL_STATUS, SEARCH_RECORDS, GET_RECORD_FIELDS,
                 SET_RECORD_FIELDS, CREATE_PAYMENT, POP_ACCOUNT, EXECUTE_LOGIC,
                 INFO, DIAGNOSTICS, SET_LOG_LEVEL, LIST_TENANT_LOGS, LOGGING, SHUTDOWN
                    -> PriorityExecutor.Priority.HIGH;
            default -> PriorityExecutor.Priority.LOW;
        };
    }

    // ========== Proto → DTO conversions ==========

    private TenantBackendClient.AgentCall convertAgentCall(AgentCall proto) {
        var dto = new TenantBackendClient.AgentCall();
        dto.agentCallSid = proto.getAgentCallSid();
        dto.callSid = proto.getCallSid();
        dto.callType = callTypeToString(proto.getCallType());
        dto.userId = proto.getUserId();
        dto.partnerAgentId = proto.getPartnerAgentId();
        dto.orgId = proto.getOrgId();
        dto.internalKey = proto.getInternalKey();
        dto.talkDuration = durationToSeconds(proto.getTalkDuration());
        dto.callWaitDuration = durationToSeconds(proto.getWaitDuration());
        dto.wrapUpDuration = durationToSeconds(proto.getWrapupDuration());
        dto.pauseDuration = durationToSeconds(proto.getPauseDuration());
        dto.transferDuration = durationToSeconds(proto.getTransferDuration());
        dto.manualDuration = durationToSeconds(proto.getManualDuration());
        dto.previewDuration = durationToSeconds(proto.getPreviewDuration());
        dto.holdDuration = durationToSeconds(proto.getHoldDuration());
        dto.agentWaitDuration = durationToSeconds(proto.getAgentWaitDuration());
        dto.suspendedDuration = durationToSeconds(proto.getSuspendedDuration());
        dto.externalTransferDuration = durationToSeconds(proto.getExternalTransferDuration());
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        return dto;
    }

    private TenantBackendClient.TelephonyResult convertTelephonyResult(TelephonyResult proto) {
        var dto = new TenantBackendClient.TelephonyResult();
        dto.callSid = proto.getCallSid();
        dto.callType = callTypeToString(proto.getCallType());
        dto.orgId = proto.getOrgId();
        dto.internalKey = proto.getInternalKey();
        dto.callerId = proto.getCallerId();
        dto.phoneNumber = proto.getPhoneNumber();
        dto.poolId = proto.getPoolId();
        dto.recordId = proto.getRecordId();
        dto.clientSid = proto.getClientSid();
        dto.status = proto.getStatus().name();
        // v3 uses TelephonyOutcome; map category to legacy "result" string.
        dto.result = proto.hasOutcome() ? proto.getOutcome().getCategory().name() : "";
        dto.deliveryLength = durationToSeconds(proto.getDeliveryLength());
        dto.linkbackLength = durationToSeconds(proto.getLinkbackLength());
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        dto.startTime = proto.hasStartTime() ? toIso(proto.getStartTime()) : null;
        dto.endTime = proto.hasEndTime() ? toIso(proto.getEndTime()) : null;
        dto.taskWaitingUntil = proto.hasTaskWaitingUntil() ? toIso(proto.getTaskWaitingUntil()) : null;
        dto.oldCallSid = proto.hasOldCallSid() ? proto.getOldCallSid() : 0;
        dto.oldCallType = proto.hasOldCallType() ? callTypeToString(proto.getOldCallType()) : null;

        // v3 task_data: repeated TaskData (key/value) instead of parallel arrays.
        if (dto.poolId == null || dto.poolId.isBlank() || dto.recordId == null || dto.recordId.isBlank()) {
            for (var td : proto.getTaskDataList()) {
                String key = td.getKey();
                String val = td.getValue().hasStringValue() ? td.getValue().getStringValue() : "";
                if ((dto.poolId == null || dto.poolId.isBlank())
                        && ("pool_id".equalsIgnoreCase(key) || "poolid".equalsIgnoreCase(key))) {
                    dto.poolId = val;
                }
                if ((dto.recordId == null || dto.recordId.isBlank())
                        && ("record_id".equalsIgnoreCase(key) || "recordid".equalsIgnoreCase(key))) {
                    dto.recordId = val;
                }
            }
        }
        return dto;
    }

    private TenantBackendClient.AgentResponse convertAgentResponse(AgentResponse proto) {
        var dto = new TenantBackendClient.AgentResponse();
        dto.agentCallResponseSid = proto.getAgentCallResponseSid();
        dto.callSid = proto.getCallSid();
        dto.callType = callTypeToString(proto.getCallType());
        dto.userId = proto.getUserId();
        dto.partnerAgentId = proto.getPartnerAgentId();
        dto.orgId = proto.getOrgId();
        dto.internalKey = proto.getInternalKey();
        dto.agentSid = proto.getAgentSid();
        dto.clientSid = proto.getClientSid();
        dto.responseKey = proto.getResponseKey();
        dto.responseValue = proto.getResponseValue();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        return dto;
    }

    private TenantBackendClient.TransferInstance convertTransferInstance(TransferInstance proto) {
        var dto = new TenantBackendClient.TransferInstance();
        dto.clientSid = proto.getClientSid();
        dto.orgId = proto.getOrgId();
        dto.transferInstanceId = String.valueOf(proto.getTransferInstanceId());
        dto.transferResult = proto.getTransferResult().name();
        dto.transferType = proto.getTransferType().name();

        if (proto.hasSource()) {
            var src = proto.getSource();
            dto.sourceCallSid = String.valueOf(src.getCallSid());
            dto.sourceCallType = callTypeToString(src.getCallType());
            dto.sourceUserId = src.getUserId();
            dto.sourcePartnerAgentId = src.getPartnerAgentId();
            dto.sourceConversationId = 0; // v3 conversation_id is string, DTO expects long
            dto.sourceSessionSid = src.getSessionSid();
        }

        if (proto.hasDestination()) {
            var dest = proto.getDestination();
            switch (dest.getTargetCase()) {
                case AGENT -> {
                    var a = dest.getAgent();
                    dto.destinationType = "agent";
                    dto.destinationPartnerAgentId = a.getPartnerAgentId();
                    dto.destinationUserId = a.getUserId();
                    dto.destinationSessionSid = a.getSessionSid();
                }
                case OUTBOUND -> {
                    var o = dest.getOutbound();
                    dto.destinationType = "call";
                    dto.destinationCallSid = String.valueOf(o.getCallSid());
                    dto.destinationCallType = callTypeToString(o.getCallType());
                    dto.destinationPhoneNumber = o.getPhoneNumber();
                }
                case QUEUE -> {
                    dto.destinationType = "queue";
                    // v3 required_skills: map<string, int64> (proficiency)
                    // v2 DTO expected map<String, Boolean>; convert to empty map (semantics changed)
                    dto.destinationSkills = new HashMap<>();
                }
                default -> dto.destinationType = "unknown";
            }
        }

        dto.pendingDurationMicroseconds = durationToMicros(proto.getPendingDuration());
        dto.externalDurationMicroseconds = durationToMicros(proto.getExternalDuration());
        dto.durationMicroseconds = durationToMicros(proto.getFullDuration());
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        dto.transferStartTime = proto.hasTransferTime() ? toIso(proto.getTransferTime()) : null;
        dto.transferEndTime = proto.hasEndTime() ? toIso(proto.getEndTime()) : null;
        return dto;
    }

    private TenantBackendClient.CallRecording convertCallRecording(CallRecording proto) {
        var dto = new TenantBackendClient.CallRecording();
        dto.recordingId = proto.getRecordingId();
        dto.orgId = proto.getOrgId();
        dto.callSid = proto.getCallSid();
        dto.callType = callTypeToString(proto.getCallType());
        dto.recordingType = proto.getRecordingType().name();
        dto.durationSeconds = proto.hasDuration() ? proto.getDuration().getSeconds() : 0;
        dto.startTime = proto.hasStartTime() ? toIso(proto.getStartTime()) : null;
        return dto;
    }

    private TenantBackendClient.ExileTask convertExileTask(ExileTask proto) {
        var dto = new TenantBackendClient.ExileTask();
        dto.taskSid = String.valueOf(proto.getTaskSid());
        dto.taskGroupSid = String.valueOf(proto.getTaskGroupSid());
        dto.orgId = proto.getOrgId();
        dto.clientSid = proto.getClientSid();
        dto.poolId = proto.getPoolId();
        dto.recordId = proto.getRecordId();
        dto.attempts = proto.getAttempts();
        dto.status = proto.getStatus().name();
        dto.createTime = proto.hasCreateTime() ? toIso(proto.getCreateTime()) : null;
        dto.updateTime = proto.hasUpdateTime() ? toIso(proto.getUpdateTime()) : null;
        return dto;
    }

    // ========== RPC callback ==========

    /**
     * Send an AddAgentCallResponse back to Gate when the backend returns an RPC value.
     */
    private void sendRpcResponse(String callSid, CallType callType, String rpcValue) {
        try {
            log.info("Sending RPC response for callSid={} key=RPC value={}", callSid, rpcValue);
            gateClient.addAgentCallResponse(
                    build.buf.gen.tcnapi.exile.gate.v3.AddAgentCallResponseRequest.newBuilder()
                            .setCallSid(Long.parseLong(callSid))
                            .setKey("RPC")
                            .setValue(rpcValue)
                            .setCallType(callType)
                            .build());
        } catch (Exception e) {
            log.error("Failed to send RPC response for callSid={}: {}", callSid, e.getMessage());
        }
    }

    // ========== Send ==========

    private void pull(int count) {
        send(WorkRequest.newBuilder().setPull(Pull.newBuilder().setMaxItems(count)).build());
    }

    private void send(WorkRequest request) {
        var observer = requestObserver.get();
        if (observer == null) return;
        try {
            synchronized (observer) {
                observer.onNext(request);
            }
        } catch (Exception e) {
            log.warn("Failed to send {}: {}", request.getActionCase(), e.getMessage());
            var current = requestObserver.getAndSet(null);
            if (current != null) {
                try { current.onError(e); } catch (Exception ignored) {}
            }
        }
    }

    // ========== Helpers ==========

    private static String callTypeToString(CallType ct) {
        return switch (ct) {
            case CALL_TYPE_INBOUND -> "inbound";
            case CALL_TYPE_OUTBOUND -> "outbound";
            case CALL_TYPE_PREVIEW -> "preview";
            case CALL_TYPE_MANUAL -> "manual";
            case CALL_TYPE_MAC -> "mac";
            default -> "unknown";
        };
    }

    private static Pool.PoolStatus mapPoolStatus(String status) {
        if (status == null) return Pool.PoolStatus.POOL_STATUS_NOT_READY;
        return switch (status.toUpperCase()) {
            case "READY" -> Pool.PoolStatus.POOL_STATUS_READY;
            case "BUSY", "BUILDING" -> Pool.PoolStatus.POOL_STATUS_BUSY;
            default -> Pool.PoolStatus.POOL_STATUS_NOT_READY;
        };
    }

    private static long durationToSeconds(com.google.protobuf.Duration d) {
        if (d == null || d.equals(com.google.protobuf.Duration.getDefaultInstance())) return 0;
        return d.getSeconds();
    }

    private static long durationToMicros(com.google.protobuf.Duration d) {
        if (d == null || d.equals(com.google.protobuf.Duration.getDefaultInstance())) return 0;
        return d.getSeconds() * 1_000_000L + d.getNanos() / 1_000L;
    }

    private static String toIso(com.google.protobuf.Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString();
    }

    private static Map<String, String> structToStringMap(com.google.protobuf.Struct s) {
        Map<String, String> result = new HashMap<>();
        if (s == null) return result;
        for (var entry : s.getFieldsMap().entrySet()) {
            var v = entry.getValue();
            String str = switch (v.getKindCase()) {
                case STRING_VALUE -> v.getStringValue();
                case NUMBER_VALUE -> String.valueOf(v.getNumberValue());
                case BOOL_VALUE -> String.valueOf(v.getBoolValue());
                default -> "";
            };
            result.put(entry.getKey(), str);
        }
        return result;
    }

    private static com.google.protobuf.Value strVal(String s) {
        return com.google.protobuf.Value.newBuilder().setStringValue(s != null ? s : "").build();
    }

    private static com.google.protobuf.Value numVal(long n) {
        return com.google.protobuf.Value.newBuilder().setNumberValue(n).build();
    }

    private static SpanContext parseTraceParent(String traceParent) {
        if (traceParent == null || traceParent.isEmpty()) return null;
        String[] parts = traceParent.split("-");
        if (parts.length < 4) return null;
        try {
            return SpanContext.createFromRemoteParent(
                    parts[1], parts[2],
                    TraceFlags.fromByte(Byte.parseByte(parts[3], 16)),
                    TraceState.getDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private static long computeBackoff(long failures) {
        if (failures <= 0) return 0;
        long delay = BACKOFF_BASE_MS * (1L << Math.min(failures - 1, 4));
        return Math.min(delay, BACKOFF_MAX_MS);
    }

    private static List<WorkType> allCapabilities() {
        return List.of(
                WorkType.WORK_TYPE_LIST_POOLS,
                WorkType.WORK_TYPE_GET_POOL_STATUS,
                WorkType.WORK_TYPE_GET_POOL_RECORDS,
                WorkType.WORK_TYPE_SEARCH_RECORDS,
                WorkType.WORK_TYPE_GET_RECORD_FIELDS,
                WorkType.WORK_TYPE_SET_RECORD_FIELDS,
                WorkType.WORK_TYPE_CREATE_PAYMENT,
                WorkType.WORK_TYPE_POP_ACCOUNT,
                WorkType.WORK_TYPE_EXECUTE_LOGIC,
                WorkType.WORK_TYPE_INFO,
                WorkType.WORK_TYPE_SHUTDOWN,
                WorkType.WORK_TYPE_LOGGING,
                WorkType.WORK_TYPE_DIAGNOSTICS,
                WorkType.WORK_TYPE_LIST_TENANT_LOGS,
                WorkType.WORK_TYPE_SET_LOG_LEVEL,
                WorkType.WORK_TYPE_AGENT_CALL,
                WorkType.WORK_TYPE_TELEPHONY_RESULT,
                WorkType.WORK_TYPE_AGENT_RESPONSE,
                WorkType.WORK_TYPE_TRANSFER_INSTANCE,
                WorkType.WORK_TYPE_CALL_RECORDING,
                WorkType.WORK_TYPE_TASK
        );
    }

    // ========== Status accessors ==========

    public boolean isConnected() {
        return requestObserver.get() != null;
    }

    public long getCompletedTotal() { return completedTotal.get(); }
    public long getFailedTotal() { return failedTotal.get(); }
    public long getReconnectAttempts() { return reconnectAttempts.get(); }
    public int getInflight() { return inflight.get(); }

    @Override
    public void close() {
        if (!draining.compareAndSet(false, true)) {
            return; // already closing — first caller handles it
        }
        phase = Phase.DRAINING;
        running.set(false);

        int remaining = inflight.get();
        log.info("WorkStream draining (inflight={}, timeout={}s)", remaining, shutdownDrainTimeout.toSeconds());

        var drainer = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "exile-work-stream-drain");
            t.setDaemon(true);
            return t;
        });
        var drainDone = drainer.submit(() -> { workerPool.close(); return null; });
        drainer.shutdown();

        boolean drained;
        try {
            drainDone.get(shutdownDrainTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            drained = true;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("WorkStream drain timed out after {}s with {} items still in-flight — forcing shutdown." +
                    " Unfinished items will be re-delivered by the server after lease expiry.",
                    shutdownDrainTimeout.toSeconds(), inflight.get());
            workerPool.shutdownNow();
            drained = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
            drained = false;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("WorkStream drain failed: {}", e.getCause() != null ? e.getCause() : e);
            drained = false;
        } finally {
            drainer.shutdownNow();
        }

        var observer = requestObserver.getAndSet(null);
        if (observer != null) {
            try { observer.onCompleted(); } catch (Exception ignored) {}
        }
        if (streamThread != null) streamThread.interrupt();
        phase = Phase.CLOSED;

        log.info("WorkStream closed (drained={}, remaining_at_start={}, completed={}, failed={})",
                drained, remaining, completedTotal.get(), failedTotal.get());
    }
}
