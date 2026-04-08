package com.tcn.exile.internal;

import static com.tcn.exile.internal.ProtoConverter.*;

import com.tcn.exile.ExileConfig;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.model.*;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.worker.v3.*;

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
  private final String clientName;
  private final String clientVersion;
  private final int maxConcurrency;
  private final List<WorkType> capabilities;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<StreamObserver<WorkRequest>> requestObserver =
      new AtomicReference<>();
  private final AtomicInteger inflight = new AtomicInteger(0);
  private final ExecutorService workerPool = Executors.newVirtualThreadPerTaskExecutor();

  private volatile ManagedChannel channel;
  private volatile Thread streamThread;

  public WorkStreamClient(
      ExileConfig config,
      JobHandler jobHandler,
      EventHandler eventHandler,
      String clientName,
      String clientVersion,
      int maxConcurrency,
      List<WorkType> capabilities) {
    this.config = config;
    this.jobHandler = jobHandler;
    this.eventHandler = eventHandler;
    this.clientName = clientName;
    this.clientVersion = clientVersion;
    this.maxConcurrency = maxConcurrency;
    this.capabilities = capabilities;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("Already started");
    }
    streamThread =
        Thread.ofPlatform().name("exile-work-stream").daemon(true).start(this::reconnectLoop);
  }

  private void reconnectLoop() {
    var backoff = new Backoff();
    while (running.get()) {
      try {
        backoff.sleep();
        runStream();
        backoff.reset();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        backoff.recordFailure();
        log.warn("Stream disconnected: {}", e.getMessage());
      }
    }
    log.info("Work stream loop exited");
  }

  private void runStream() throws InterruptedException {
    channel = ChannelFactory.create(config);
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
                  log.warn("Stream error: {}", t.getMessage());
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  log.info("Stream completed by server");
                  latch.countDown();
                }
              });

      requestObserver.set(observer);

      send(
          WorkRequest.newBuilder()
              .setRegister(
                  Register.newBuilder()
                      .setClientName(clientName)
                      .setClientVersion(clientVersion)
                      .addAllCapabilities(capabilities))
              .build());

      pull(maxConcurrency);
      latch.await();
    } finally {
      requestObserver.set(null);
      inflight.set(0);
      ChannelFactory.shutdown(channel);
      channel = null;
    }
  }

  private void handleResponse(WorkResponse response) {
    switch (response.getPayloadCase()) {
      case REGISTERED -> {
        var reg = response.getRegistered();
        log.info(
            "Registered as {} (heartbeat={}s, lease={}s, max_inflight={})",
            reg.getClientId(),
            reg.getHeartbeatInterval().getSeconds(),
            reg.getDefaultLease().getSeconds(),
            reg.getMaxInflight());
      }
      case WORK_ITEM -> {
        inflight.incrementAndGet();
        workerPool.submit(() -> processWorkItem(response.getWorkItem()));
      }
      case RESULT_ACCEPTED ->
          log.debug("Result accepted: {}", response.getResultAccepted().getWorkId());
      case LEASE_EXPIRING -> {
        var w = response.getLeaseExpiring();
        log.debug("Lease expiring for {}, {}s remaining", w.getWorkId(),
            w.getRemaining().getSeconds());
        send(
            WorkRequest.newBuilder()
                .setExtendLease(
                    ExtendLease.newBuilder()
                        .setWorkId(w.getWorkId())
                        .setExtension(
                            com.google.protobuf.Duration.newBuilder().setSeconds(300)))
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
        log.warn("Stream error for {}: {} - {}", err.getWorkId(), err.getCode(),
            err.getMessage());
      }
      default -> {}
    }
  }

  private void processWorkItem(WorkItem item) {
    String workId = item.getWorkId();
    try {
      if (item.getCategory() == WorkCategory.WORK_CATEGORY_JOB) {
        var result = dispatchJob(item);
        send(WorkRequest.newBuilder().setResult(result).build());
      } else {
        dispatchEvent(item);
        send(WorkRequest.newBuilder().setAck(Ack.newBuilder().addWorkIds(workId)).build());
      }
    } catch (Exception e) {
      log.warn("Work item {} failed: {}", workId, e.getMessage());
      send(
          WorkRequest.newBuilder()
              .setResult(
                  Result.newBuilder()
                      .setWorkId(workId)
                      .setFinal(true)
                      .setError(ErrorResult.newBuilder().setMessage(e.getMessage())))
              .build());
    } finally {
      inflight.decrementAndGet();
      pull(1);
    }
  }

  // ---- Job dispatch: call handler with plain Java types, convert result to proto ----

  private Result.Builder dispatchJob(WorkItem item) throws Exception {
    var b = Result.newBuilder().setWorkId(item.getWorkId()).setFinal(true);

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
        var page = jobHandler.getPoolRecords(task.getPoolId(), task.getPageToken(),
            task.getPageSize());
        b.setGetPoolRecords(
            GetPoolRecordsResult.newBuilder()
                .addAllRecords(page.items().stream().map(ProtoConverter::fromRecord).toList())
                .setNextPageToken(page.nextPageToken() != null ? page.nextPageToken() : ""));
      }
      case SEARCH_RECORDS -> {
        var task = item.getSearchRecords();
        var filters = task.getFiltersList().stream().map(ProtoConverter::toFilter).toList();
        var page = jobHandler.searchRecords(filters, task.getPageToken(), task.getPageSize());
        b.setSearchRecords(
            SearchRecordsResult.newBuilder()
                .addAllRecords(page.items().stream().map(ProtoConverter::fromRecord).toList())
                .setNextPageToken(page.nextPageToken() != null ? page.nextPageToken() : ""));
      }
      case GET_RECORD_FIELDS -> {
        var task = item.getGetRecordFields();
        var fields = jobHandler.getRecordFields(task.getPoolId(), task.getRecordId(),
            task.getFieldNamesList());
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
        var paymentId = jobHandler.createPayment(task.getPoolId(), task.getRecordId(),
            structToMap(task.getPaymentData()));
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
        var output = jobHandler.executeLogic(task.getLogicName(),
            structToMap(task.getParameters()));
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
        var page = jobHandler.listTenantLogs(
            toInstant(task.getStartTime()), toInstant(task.getEndTime()),
            task.getPageToken(), task.getPageSize());
        var rb = ListTenantLogsResult.newBuilder()
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
    return b;
  }

  // ---- Event dispatch: convert proto to plain Java, call handler ----

  private void dispatchEvent(WorkItem item) throws Exception {
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
      case TASK -> eventHandler.onTask(toTaskEvent(item.getTask()));
      default -> throw new UnsupportedOperationException("Unknown event: " + item.getTaskCase());
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
      }
    }
  }

  @Override
  public void close() {
    running.set(false);
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
