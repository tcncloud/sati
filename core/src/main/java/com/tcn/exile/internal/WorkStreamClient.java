package com.tcn.exile.internal;

import com.tcn.exile.ExileConfig;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.worker.v3.*;

/**
 * Implements the v3 WorkStream protocol over a single bidirectional gRPC stream.
 *
 * <p>Lifecycle: {@link #start()} opens the stream in a background thread that reconnects
 * automatically. {@link #close()} shuts everything down.
 *
 * <p>The client uses credit-based flow control: it sends {@code Pull(max_items=N)} to control how
 * many concurrent work items it processes. Work items are dispatched to virtual threads, so the
 * main stream thread never blocks on handler execution.
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

  /** Start the work stream in a background thread. Returns immediately. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("Already started");
    }
    streamThread =
        Thread.ofPlatform()
            .name("exile-work-stream")
            .daemon(true)
            .start(this::reconnectLoop);
  }

  private void reconnectLoop() {
    var backoff = new Backoff();
    while (running.get()) {
      try {
        backoff.sleep();
        runStream();
        // Stream ended normally (server closed). Reset and reconnect.
        backoff.reset();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        backoff.recordFailure();
        log.warn("Stream disconnected (attempt {}): {}", backoff, e.getMessage());
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

      // 1. Register
      send(
          WorkRequest.newBuilder()
              .setRegister(
                  Register.newBuilder()
                      .setClientName(clientName)
                      .setClientVersion(clientVersion)
                      .addAllCapabilities(capabilities))
              .build());

      // 2. Initial pull
      pull(maxConcurrency);

      // 3. Wait until stream ends
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
        var item = response.getWorkItem();
        inflight.incrementAndGet();
        workerPool.submit(() -> processWorkItem(item));
      }

      case RESULT_ACCEPTED -> {
        log.debug("Result accepted: {}", response.getResultAccepted().getWorkId());
      }

      case LEASE_EXPIRING -> {
        var warning = response.getLeaseExpiring();
        log.debug(
            "Lease expiring for {}, {}s remaining",
            warning.getWorkId(),
            warning.getRemaining().getSeconds());
        // Auto-extend by default. Integrations can override via ExileClient.Builder.
        send(
            WorkRequest.newBuilder()
                .setExtendLease(
                    ExtendLease.newBuilder()
                        .setWorkId(warning.getWorkId())
                        .setExtension(
                            com.google.protobuf.Duration.newBuilder().setSeconds(300).build()))
                .build());
      }

      case LEASE_EXTENDED -> {
        log.debug("Lease extended for {}", response.getLeaseExtended().getWorkId());
      }

      case NACK_ACCEPTED -> {
        log.debug("Nack accepted: {}", response.getNackAccepted().getWorkId());
      }

      case HEARTBEAT -> {
        send(
            WorkRequest.newBuilder()
                .setHeartbeat(
                    Heartbeat.newBuilder()
                        .setClientTime(
                            com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(Instant.now().getEpochSecond())))
                .build());
      }

      case ERROR -> {
        var err = response.getError();
        log.warn("Stream error for {}: {} - {}", err.getWorkId(), err.getCode(), err.getMessage());
      }

      default -> log.debug("Unknown response type: {}", response.getPayloadCase());
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
      pull(1); // Replenish one slot.
    }
  }

  private Result.Builder dispatchJob(WorkItem item) throws Exception {
    var b = Result.newBuilder().setWorkId(item.getWorkId()).setFinal(true);
    switch (item.getTaskCase()) {
      case LIST_POOLS -> b.setListPools(jobHandler.listPools(item.getListPools()));
      case GET_POOL_STATUS -> b.setGetPoolStatus(jobHandler.getPoolStatus(item.getGetPoolStatus()));
      case GET_POOL_RECORDS ->
          b.setGetPoolRecords(jobHandler.getPoolRecords(item.getGetPoolRecords()));
      case SEARCH_RECORDS -> b.setSearchRecords(jobHandler.searchRecords(item.getSearchRecords()));
      case GET_RECORD_FIELDS ->
          b.setGetRecordFields(jobHandler.getRecordFields(item.getGetRecordFields()));
      case SET_RECORD_FIELDS ->
          b.setSetRecordFields(jobHandler.setRecordFields(item.getSetRecordFields()));
      case CREATE_PAYMENT -> b.setCreatePayment(jobHandler.createPayment(item.getCreatePayment()));
      case POP_ACCOUNT -> b.setPopAccount(jobHandler.popAccount(item.getPopAccount()));
      case EXECUTE_LOGIC -> b.setExecuteLogic(jobHandler.executeLogic(item.getExecuteLogic()));
      case INFO -> b.setInfo(jobHandler.info(item.getInfo()));
      case SHUTDOWN -> b.setShutdown(jobHandler.shutdown(item.getShutdown()));
      case LOGGING -> b.setLogging(jobHandler.logging(item.getLogging()));
      case DIAGNOSTICS -> b.setDiagnostics(jobHandler.diagnostics(item.getDiagnostics()));
      case LIST_TENANT_LOGS ->
          b.setListTenantLogs(jobHandler.listTenantLogs(item.getListTenantLogs()));
      case SET_LOG_LEVEL -> b.setSetLogLevel(jobHandler.setLogLevel(item.getSetLogLevel()));
      default -> throw new UnsupportedOperationException("Unknown job type: " + item.getTaskCase());
    }
    return b;
  }

  private void dispatchEvent(WorkItem item) throws Exception {
    switch (item.getTaskCase()) {
      case AGENT_CALL -> eventHandler.onAgentCall(item.getAgentCall());
      case TELEPHONY_RESULT -> eventHandler.onTelephonyResult(item.getTelephonyResult());
      case AGENT_RESPONSE -> eventHandler.onAgentResponse(item.getAgentResponse());
      case TRANSFER_INSTANCE -> eventHandler.onTransferInstance(item.getTransferInstance());
      case CALL_RECORDING -> eventHandler.onCallRecording(item.getCallRecording());
      case TASK -> eventHandler.onTask(item.getTask());
      default ->
          throw new UnsupportedOperationException("Unknown event type: " + item.getTaskCase());
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
    if (streamThread != null) {
      streamThread.interrupt();
    }
    var observer = requestObserver.getAndSet(null);
    if (observer != null) {
      try {
        observer.onCompleted();
      } catch (Exception ignored) {
      }
    }
    workerPool.close();
    if (channel != null) {
      ChannelFactory.shutdown(channel);
    }
  }
}
