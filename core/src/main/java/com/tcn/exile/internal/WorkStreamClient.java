package com.tcn.exile.internal;

import static com.tcn.exile.internal.ProtoConverter.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.StreamStatus;
import com.tcn.exile.StreamStatus.Phase;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.model.*;
import io.grpc.ManagedChannel;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Implements the v3 WorkStream protocol over a single bidirectional gRPC stream.
 *
 * <p>This class is internal. The public API is {@link com.tcn.exile.ExileClient}. All proto types
 * are converted to/from plain Java types at the boundary — handlers never see proto classes.
 */
public final class WorkStreamClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(WorkStreamClient.class);

  private final ExileConfig config;
  private final JobHandler jobHandler;
  private final EventHandler eventHandler;
  private final IntSupplier capacityProvider;
  private final String clientName;
  private final String clientVersion;
  private final int maxConcurrency;
  private final List<WorkType> capabilities;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<StreamObserver<WorkRequest>> requestObserver =
      new AtomicReference<>();
  private final AtomicInteger inflight = new AtomicInteger(0);
  private final ExecutorService workerPool = Executors.newVirtualThreadPerTaskExecutor();

  // Credit-based flow control mirror. Tracks credits we have granted the server via Pull
  // messages that have not yet been consumed by an incoming WorkItem. When this drops below the
  // plugin's reported {@code availableCapacity()} we top it up; if the plugin reports 0 we stop
  // sending Pulls and the gate backs off automatically.
  private final AtomicInteger outstandingCredits = new AtomicInteger(0);
  private volatile ScheduledExecutorService creditReplenishExecutor;
  private volatile ScheduledFuture<?> creditReplenishTask;

  // Maps work_id → SpanContext so async responses (ResultAccepted, etc.) can log with trace
  // context.
  private final java.util.concurrent.ConcurrentHashMap<String, SpanContext> workSpanContexts =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Status tracking.
  private volatile Phase phase = Phase.IDLE;
  private volatile String clientId;
  private volatile Instant connectedSince;
  private volatile Instant lastDisconnect;
  private volatile String lastError;
  private final AtomicLong completedTotal = new AtomicLong(0);
  private final AtomicLong failedTotal = new AtomicLong(0);
  private final AtomicLong reconnectAttempts = new AtomicLong(0);

  private volatile ManagedChannel channel;
  private volatile Thread streamThread;
  private volatile java.util.function.DoubleConsumer durationRecorder;
  private volatile java.util.function.DoubleConsumer reconnectRecorder;
  private volatile boolean lastDisconnectGraceful;
  private volatile MethodRecorder methodRecorder;

  /** Callback to record per-method metrics (name, duration, success). */
  @FunctionalInterface
  public interface MethodRecorder {
    void record(String method, double durationSeconds, boolean success);
  }

  public WorkStreamClient(
      ExileConfig config,
      JobHandler jobHandler,
      EventHandler eventHandler,
      String clientName,
      String clientVersion,
      int maxConcurrency,
      List<WorkType> capabilities) {
    this(
        config,
        jobHandler,
        eventHandler,
        () -> Integer.MAX_VALUE,
        clientName,
        clientVersion,
        maxConcurrency,
        capabilities);
  }

  /**
   * Construct with an explicit capacity provider, used to apply credit-based backpressure against
   * the gate server. See {@link com.tcn.exile.handler.Plugin#availableCapacity()}.
   */
  public WorkStreamClient(
      ExileConfig config,
      JobHandler jobHandler,
      EventHandler eventHandler,
      IntSupplier capacityProvider,
      String clientName,
      String clientVersion,
      int maxConcurrency,
      List<WorkType> capabilities) {
    this.config = config;
    this.jobHandler = jobHandler;
    this.eventHandler = eventHandler;
    this.capacityProvider = capacityProvider != null ? capacityProvider : () -> Integer.MAX_VALUE;
    this.clientName = clientName;
    this.clientVersion = clientVersion;
    this.maxConcurrency = maxConcurrency;
    this.capabilities = capabilities;
  }

  /** Returns a snapshot of the stream's current state. */
  public StreamStatus status() {
    return new StreamStatus(
        phase,
        clientId,
        connectedSince,
        lastDisconnect,
        lastError,
        inflight.get(),
        completedTotal.get(),
        failedTotal.get(),
        reconnectAttempts.get());
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("Already started");
    }
    log.debug("Creating gRPC channel to {}:{}", config.apiHostname(), config.apiPort());
    channel = ChannelFactory.create(config);
    log.debug("Channel created");
    streamThread =
        Thread.ofPlatform().name("exile-work-stream").daemon(true).start(this::reconnectLoop);
  }

  private void reconnectLoop() {
    var backoff = new Backoff();
    while (running.get()) {
      try {
        long delayMs = backoff.nextDelayMs();
        if (delayMs > 0) {
          phase = Phase.RECONNECTING;
          log.info("Reconnecting in {}ms (attempt #{})", delayMs, reconnectAttempts.get() + 1);
        }
        backoff.sleep();
        long attempt = reconnectAttempts.incrementAndGet();
        log.info(
            "Connecting to {}:{} (attempt #{})", config.apiHostname(), config.apiPort(), attempt);
        runStream();
        // Only reset backoff if the stream ended gracefully (RST_STREAM NO_ERROR or server close).
        // UNAVAILABLE and other errors should trigger backoff to avoid hammering the server.
        if (lastDisconnectGraceful) {
          backoff.reset();
        } else {
          backoff.recordFailure();
        }
        lastDisconnectGraceful = false;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        backoff.recordFailure();
        lastDisconnect = Instant.now();
        lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        connectedSince = null;
        clientId = null;
        lastDisconnectGraceful = false;
        log.warn("Stream disconnected ({}): {}", e.getClass().getSimpleName(), e.getMessage());
      }
    }
    phase = Phase.CLOSED;
    log.info("Work stream loop exited");
  }

  private void runStream() throws InterruptedException {
    phase = Phase.CONNECTING;
    log.debug("Opening WorkStream on existing channel");
    try {
      var stub = WorkerServiceGrpc.newStub(channel);
      var latch = new CountDownLatch(1);

      var observer =
          stub.workStream(
              new StreamObserver<>() {
                @Override
                public void onNext(WorkResponse response) {
                  handleResponse(response);
                }

                @Override
                public void onError(Throwable t) {
                  lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                  lastDisconnect = Instant.now();
                  connectedSince = null;
                  // RST_STREAM with NO_ERROR is envoy recycling the stream, not a real failure.
                  String msg = t.getMessage();
                  lastDisconnectGraceful =
                      msg != null && msg.contains("RST_STREAM") && msg.contains("NO_ERROR");
                  log.warn("Stream error ({}): {}", t.getClass().getSimpleName(), t.getMessage());
                  // Log the full cause chain for SSL errors to aid debugging.
                  for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
                    log.warn(
                        "  caused by ({}): {}",
                        cause.getClass().getSimpleName(),
                        cause.getMessage());
                  }
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  lastDisconnect = Instant.now();
                  connectedSince = null;
                  lastDisconnectGraceful = true;
                  log.info("Stream completed by server");
                  latch.countDown();
                }
              });

      requestObserver.set(observer);

      // Register.
      phase = Phase.REGISTERING;
      send(
          WorkRequest.newBuilder()
              .setRegister(
                  Register.newBuilder()
                      .setClientName(clientName)
                      .setClientVersion(clientVersion)
                      .addAllCapabilities(capabilities))
              .build());

      // Wait until stream ends.
      latch.await();
    } finally {
      requestObserver.set(null);
      inflight.set(0);
      outstandingCredits.set(0);
      stopCreditReplenisher();
      // Channel is reused across reconnects — only shut down on close().
    }
  }

  // ── Credit-based flow control ──────────────────────────────────────────────

  /**
   * Top up the server's remaining credit balance so it matches the plugin's current willingness to
   * accept work, capped by {@code maxConcurrency}. Called on every WorkItem received and on a
   * periodic timer so credits can flow back even while the server is idle waiting.
   */
  private void replenishCredits() {
    if (phase != Phase.ACTIVE && phase != Phase.REGISTERING) return;
    int target = capacityTarget();
    if (target <= 0) return; // plugin has no capacity — don't grant any credits
    int outstanding = outstandingCredits.get();
    if (outstanding >= target) return;
    int delta;
    while (true) {
      outstanding = outstandingCredits.get();
      if (outstanding >= target) return;
      delta = target - outstanding;
      if (outstandingCredits.compareAndSet(outstanding, outstanding + delta)) break;
    }
    pull(delta);
  }

  private int capacityTarget() {
    int cap;
    try {
      cap = capacityProvider.getAsInt();
    } catch (Throwable t) {
      log.warn("capacityProvider threw {}; falling back to maxConcurrency", t.toString());
      cap = Integer.MAX_VALUE;
    }
    if (cap < 0) cap = 0;
    int bound = maxConcurrency > 0 ? maxConcurrency : Integer.MAX_VALUE;
    return Math.min(bound, cap);
  }

  private void startCreditReplenisher() {
    stopCreditReplenisher();
    var exec =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "exile-credit-replenisher");
              t.setDaemon(true);
              return t;
            });
    creditReplenishExecutor = exec;
    creditReplenishTask =
        exec.scheduleAtFixedRate(
            () -> {
              try {
                replenishCredits();
              } catch (Throwable t) {
                log.warn("credit replenish failed: {}", t.toString());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);
  }

  private void stopCreditReplenisher() {
    var task = creditReplenishTask;
    if (task != null) task.cancel(false);
    creditReplenishTask = null;
    var exec = creditReplenishExecutor;
    if (exec != null) exec.shutdownNow();
    creditReplenishExecutor = null;
  }

  private void handleResponse(WorkResponse response) {
    switch (response.getPayloadCase()) {
      case REGISTERED -> {
        var reg = response.getRegistered();
        clientId = reg.getClientId();
        var now = Instant.now();
        // Record reconnect duration if this is a re-registration.
        var disconnectTime = lastDisconnect;
        if (disconnectTime != null) {
          double reconnectSec = java.time.Duration.between(disconnectTime, now).toMillis() / 1000.0;
          var rr = reconnectRecorder;
          if (rr != null) rr.accept(reconnectSec);
          log.info(
              "Reconnected in {}ms", java.time.Duration.between(disconnectTime, now).toMillis());
          lastDisconnect = null;
        }
        connectedSince = now;
        phase = Phase.ACTIVE;
        log.info(
            "Registered as {} (heartbeat={}s, lease={}s, max_inflight={})",
            reg.getClientId(),
            reg.getHeartbeatInterval().getSeconds(),
            reg.getDefaultLease().getSeconds(),
            reg.getMaxInflight());
        // Grant the initial batch of credits. The credit refill loop keeps the server's
        // remaining-credit count in sync with the plugin's reported availableCapacity().
        outstandingCredits.set(0);
        replenishCredits();
        startCreditReplenisher();
      }
      case WORK_ITEM -> {
        inflight.incrementAndGet();
        // Server just consumed one credit by sending us this item. Do NOT replenish here: the
        // plugin's availableCapacity() won't reflect this item yet (we haven't dispatched it to
        // the virtual-thread handler). Replenishment happens after the handler completes and on
        // the periodic timer, which together drive steady-state flow.
        outstandingCredits.decrementAndGet();
        workerPool.submit(() -> processWorkItem(response.getWorkItem()));
      }
      case RESULT_ACCEPTED -> {
        var workId = response.getResultAccepted().getWorkId();
        withWorkSpan(workId, () -> log.debug("Result accepted: {}", workId));
        workSpanContexts.remove(workId);
      }
      case LEASE_EXPIRING -> {
        var w = response.getLeaseExpiring();
        withWorkSpan(
            w.getWorkId(),
            () ->
                log.debug(
                    "Lease expiring for {}, {}s remaining",
                    w.getWorkId(),
                    w.getRemaining().getSeconds()));
        send(
            WorkRequest.newBuilder()
                .setExtendLease(
                    ExtendLease.newBuilder()
                        .setWorkId(w.getWorkId())
                        .setExtension(com.google.protobuf.Duration.newBuilder().setSeconds(300)))
                .build());
      }
      case HEARTBEAT ->
          send(
              WorkRequest.newBuilder()
                  .setHeartbeat(
                      Heartbeat.newBuilder()
                          .setClientTime(
                              com.google.protobuf.Timestamp.newBuilder()
                                  .setSeconds(Instant.now().getEpochSecond())))
                  .build());
      case ERROR -> {
        var err = response.getError();
        lastError = err.getCode() + ": " + err.getMessage();
        withWorkSpan(
            err.getWorkId(),
            () ->
                log.warn(
                    "Stream error for {}: {} - {}",
                    err.getWorkId(),
                    err.getCode(),
                    err.getMessage()));
      }
      default -> {}
    }
  }

  /** Set a callback to record work item processing duration (in seconds). */
  public void setDurationRecorder(java.util.function.DoubleConsumer recorder) {
    this.durationRecorder = recorder;
  }

  /** Set a callback to record per-method metrics. */
  public void setMethodRecorder(MethodRecorder recorder) {
    this.methodRecorder = recorder;
  }

  /** Set a callback to record reconnect duration (in seconds). */
  public void setReconnectRecorder(java.util.function.DoubleConsumer recorder) {
    this.reconnectRecorder = recorder;
  }

  private static final Tracer tracer = GlobalOpenTelemetry.getTracer("com.tcn.exile.sati", "1.0.0");

  /** Run a block with the span context of a work item temporarily set as current. */
  private void withWorkSpan(String workId, Runnable action) {
    var sc = workSpanContexts.get(workId);
    if (sc != null) {
      try (Scope ignored = Context.current().with(Span.wrap(sc)).makeCurrent()) {
        MDC.put("traceId", sc.getTraceId());
        MDC.put("spanId", sc.getSpanId());
        action.run();
      } finally {
        MDC.remove("traceId");
        MDC.remove("spanId");
      }
    } else {
      action.run();
    }
  }

  /** Parse a W3C traceparent string ("00-traceId-spanId-flags") into a SpanContext. */
  private static SpanContext parseTraceParent(String traceParent) {
    if (traceParent == null || traceParent.isEmpty()) return null;
    String[] parts = traceParent.split("-");
    if (parts.length < 4) return null;
    try {
      return SpanContext.createFromRemoteParent(
          parts[1],
          parts[2],
          TraceFlags.fromByte(Byte.parseByte(parts[3], 16)),
          TraceState.getDefault());
    } catch (Exception e) {
      return null;
    }
  }

  private void processWorkItem(WorkItem item) {
    long startNanos = System.nanoTime();
    String workId = item.getWorkId();
    String category = item.getCategory() == WorkCategory.WORK_CATEGORY_JOB ? "job" : "event";

    var spanBuilder =
        tracer
            .spanBuilder("exile.work." + category)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("exile.work_id", workId)
            .setAttribute("exile.work_category", category);

    // Link to the upstream trace from the gate if trace_parent is set.
    var parentCtx = parseTraceParent(item.getTraceParent());
    if (parentCtx != null) {
      spanBuilder.setParent(Context.current().with(Span.wrap(parentCtx)));
    }

    Span span = spanBuilder.startSpan();
    workSpanContexts.put(workId, span.getSpanContext());

    try (Scope ignored = span.makeCurrent()) {
      MDC.put("traceId", span.getSpanContext().getTraceId());
      MDC.put("spanId", span.getSpanContext().getSpanId());
      if (item.getCategory() == WorkCategory.WORK_CATEGORY_JOB) {
        var result = dispatchJob(item);
        send(WorkRequest.newBuilder().setResult(result).build());
      } else {
        dispatchEvent(item);
        send(WorkRequest.newBuilder().setAck(Ack.newBuilder().addWorkIds(workId)).build());
      }
      completedTotal.incrementAndGet();
    } catch (Exception e) {
      span.setStatus(StatusCode.ERROR, e.getMessage());
      span.recordException(e);
      failedTotal.incrementAndGet();
      log.warn("Work item {} failed: {}", workId, e.getMessage());
      if (item.getCategory() == WorkCategory.WORK_CATEGORY_JOB) {
        send(
            WorkRequest.newBuilder()
                .setResult(
                    Result.newBuilder()
                        .setWorkId(workId)
                        .setFinal(true)
                        .setError(ErrorResult.newBuilder().setMessage(e.getMessage())))
                .build());
      } else {
        send(
            WorkRequest.newBuilder()
                .setNack(Nack.newBuilder().setWorkId(workId).setReason(e.getMessage()))
                .build());
      }
    } finally {
      MDC.remove("traceId");
      MDC.remove("spanId");
      span.end();
      // For events (ACK), clean up now — no RESULT_ACCEPTED will come.
      // For jobs, clean up in handleResponse when RESULT_ACCEPTED is received.
      if (item.getCategory() != WorkCategory.WORK_CATEGORY_JOB) {
        workSpanContexts.remove(workId);
      }
      var recorder = durationRecorder;
      if (recorder != null) {
        recorder.accept((System.nanoTime() - startNanos) / 1_000_000_000.0);
      }
      inflight.decrementAndGet();
      // Now that the plugin has finished processing this item, its availableCapacity() reflects
      // the freed slot. Grant back credits so the gate can send another.
      replenishCredits();
    }
  }

  private Result.Builder dispatchJob(WorkItem item) throws Exception {
    var b = Result.newBuilder().setWorkId(item.getWorkId()).setFinal(true);
    var methodName = item.getTaskCase().name().toLowerCase();
    long methodStart = System.nanoTime();
    boolean methodSuccess = false;
    try {
      switch (item.getTaskCase()) {
        case LIST_POOLS -> {
          var pools = jobHandler.listPools();
          b.setListPools(
              ListPoolsResult.newBuilder()
                  .addAllPools(pools.stream().map(ProtoConverter::fromPool).toList()));
        }
        case GET_POOL_STATUS -> {
          var pool = jobHandler.getPoolStatus(item.getGetPoolStatus().getPoolId());
          b.setGetPoolStatus(GetPoolStatusResult.newBuilder().setPool(fromPool(pool)));
        }
        case GET_POOL_RECORDS -> {
          var task = item.getGetPoolRecords();
          var page =
              jobHandler.getPoolRecords(task.getPoolId(), task.getPageToken(), task.getPageSize());
          b.setGetPoolRecords(
              GetPoolRecordsResult.newBuilder()
                  .addAllRecords(
                      page.items().stream()
                          .<build.buf.gen.tcnapi.exile.gate.v3.Record>map(
                              r -> ProtoConverter.fromRecord(r))
                          .toList())
                  .setNextPageToken(page.nextPageToken() != null ? page.nextPageToken() : ""));
        }
        case SEARCH_RECORDS -> {
          var task = item.getSearchRecords();
          var filters = task.getFiltersList().stream().map(ProtoConverter::toFilter).toList();
          var page = jobHandler.searchRecords(filters, task.getPageToken(), task.getPageSize());
          b.setSearchRecords(
              SearchRecordsResult.newBuilder()
                  .addAllRecords(
                      page.items().stream()
                          .<build.buf.gen.tcnapi.exile.gate.v3.Record>map(
                              r -> ProtoConverter.fromRecord(r))
                          .toList())
                  .setNextPageToken(page.nextPageToken() != null ? page.nextPageToken() : ""));
        }
        case GET_RECORD_FIELDS -> {
          var task = item.getGetRecordFields();
          var fields =
              jobHandler.getRecordFields(
                  task.getPoolId(), task.getRecordId(), task.getFieldNamesList());
          b.setGetRecordFields(
              GetRecordFieldsResult.newBuilder()
                  .addAllFields(fields.stream().map(ProtoConverter::fromField).toList()));
        }
        case SET_RECORD_FIELDS -> {
          var task = item.getSetRecordFields();
          var fields = task.getFieldsList().stream().map(ProtoConverter::toField).toList();
          var ok = jobHandler.setRecordFields(task.getPoolId(), task.getRecordId(), fields);
          b.setSetRecordFields(SetRecordFieldsResult.newBuilder().setSuccess(ok));
        }
        case CREATE_PAYMENT -> {
          var task = item.getCreatePayment();
          var paymentId =
              jobHandler.createPayment(
                  task.getPoolId(), task.getRecordId(), structToMap(task.getPaymentData()));
          b.setCreatePayment(
              CreatePaymentResult.newBuilder().setSuccess(true).setPaymentId(paymentId));
        }
        case POP_ACCOUNT -> {
          var task = item.getPopAccount();
          var record = jobHandler.popAccount(task.getPoolId(), task.getRecordId());
          b.setPopAccount(PopAccountResult.newBuilder().setRecord(fromRecord(record)));
        }
        case EXECUTE_LOGIC -> {
          var task = item.getExecuteLogic();
          var output =
              jobHandler.executeLogic(task.getLogicName(), structToMap(task.getParameters()));
          b.setExecuteLogic(ExecuteLogicResult.newBuilder().setOutput(mapToStruct(output)));
        }
        case INFO -> {
          var info = jobHandler.info();
          var ib = InfoResult.newBuilder();
          if (info.containsKey("appName")) ib.setAppName((String) info.get("appName"));
          if (info.containsKey("appVersion")) ib.setAppVersion((String) info.get("appVersion"));
          ib.setMetadata(mapToStruct(info));
          b.setInfo(ib);
        }
        case SHUTDOWN -> {
          jobHandler.shutdown(item.getShutdown().getReason());
          b.setShutdown(ShutdownResult.getDefaultInstance());
        }
        case LOGGING -> {
          jobHandler.processLog(item.getLogging().getPayload());
          b.setLogging(LoggingResult.getDefaultInstance());
        }
        case DIAGNOSTICS -> {
          var diag = jobHandler.diagnostics();
          b.setDiagnostics(
              DiagnosticsResult.newBuilder()
                  .setSystemInfo(mapToStruct(diag.systemInfo()))
                  .setRuntimeInfo(mapToStruct(diag.runtimeInfo()))
                  .setDatabaseInfo(mapToStruct(diag.databaseInfo()))
                  .setCustom(mapToStruct(diag.custom())));
        }
        case LIST_TENANT_LOGS -> {
          var task = item.getListTenantLogs();
          var page =
              jobHandler.listTenantLogs(
                  toInstant(task.getStartTime()),
                  toInstant(task.getEndTime()),
                  task.getPageToken(),
                  task.getPageSize());
          var rb =
              ListTenantLogsResult.newBuilder()
                  .setNextPageToken(page.nextPageToken() != null ? page.nextPageToken() : "");
          for (var entry : page.items()) {
            rb.addEntries(
                ListTenantLogsResult.LogEntry.newBuilder()
                    .setTimestamp(fromInstant(entry.timestamp()))
                    .setLevel(entry.level())
                    .setLogger(entry.logger())
                    .setMessage(entry.message()));
          }
          b.setListTenantLogs(rb);
        }
        case SET_LOG_LEVEL -> {
          var task = item.getSetLogLevel();
          jobHandler.setLogLevel(task.getLoggerName(), task.getLevel());
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

  private void dispatchEvent(WorkItem item) throws Exception {
    var methodName = item.getTaskCase().name().toLowerCase();
    long methodStart = System.nanoTime();
    boolean methodSuccess = false;
    try {
      switch (item.getTaskCase()) {
        case AGENT_CALL -> eventHandler.onAgentCall(toAgentCallEvent(item.getAgentCall()));
        case TELEPHONY_RESULT ->
            eventHandler.onTelephonyResult(toTelephonyResultEvent(item.getTelephonyResult()));
        case AGENT_RESPONSE ->
            eventHandler.onAgentResponse(toAgentResponseEvent(item.getAgentResponse()));
        case TRANSFER_INSTANCE ->
            eventHandler.onTransferInstance(toTransferInstanceEvent(item.getTransferInstance()));
        case CALL_RECORDING ->
            eventHandler.onCallRecording(toCallRecordingEvent(item.getCallRecording()));
        case EXILE_TASK -> eventHandler.onTask(toTaskEvent(item.getExileTask()));
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

  private void pull(int count) {
    send(WorkRequest.newBuilder().setPull(Pull.newBuilder().setMaxItems(count)).build());
  }

  private void send(WorkRequest request) {
    var observer = requestObserver.get();
    if (observer != null) {
      try {
        synchronized (observer) {
          observer.onNext(request);
        }
      } catch (Exception e) {
        log.warn("Failed to send {}: {}", request.getActionCase(), e.getMessage());
        // Stream is broken — cancel it so the reconnect loop picks up immediately
        // instead of waiting for the next Recv to fail.
        var current = requestObserver.getAndSet(null);
        if (current != null) {
          try {
            current.onError(e);
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  @Override
  public void close() {
    running.set(false);
    phase = Phase.CLOSED;
    stopCreditReplenisher();
    if (streamThread != null) streamThread.interrupt();
    var observer = requestObserver.getAndSet(null);
    if (observer != null) {
      try {
        observer.onCompleted();
      } catch (Exception ignored) {
      }
    }
    workerPool.close();
    if (channel != null) ChannelFactory.shutdown(channel);
  }
}
