package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.StreamStatus;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.model.event.AgentCallEvent;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WorkStreamClient#close()} graceful-drain semantics. Covers three scenarios:
 * handlers finish and their Acks reach the server within the timeout; no Pulls are issued during
 * drain; handlers that exceed the timeout are interrupted.
 */
class WorkStreamClientShutdownTest {

  private static final String SERVER_NAME = "shutdown-test";

  private Server server;
  private ManagedChannel channel;
  private ShutdownWorkerService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new ShutdownWorkerService();
    server =
        InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(service)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * Handler is running when close() starts. Drain finishes the handler normally and its Ack reaches
   * the server before the stream half-closes.
   */
  @Test
  void drain_allowsInFlightHandlerToFinishAndAck() throws Exception {
    var handlerEntered = new CountDownLatch(1);
    var handlerRelease = new CountDownLatch(1);
    var handlerExited = new AtomicBoolean(false);

    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(AgentCallEvent event) {
            handlerEntered.countDown();
            try {
              handlerRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              handlerExited.set(true);
            }
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            () -> 10,
            "test",
            "1.0",
            100,
            List.of(),
            Duration.ofSeconds(5));
    client.start(channel);

    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS), "registered");
    service.sendWorkItem("w-drain-1", WorkCategory.WORK_CATEGORY_EVENT);
    assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "handler running");

    // Kick off close on a background thread; give the handler a moment then release it.
    var closeThread = new Thread(client::close, "test-closer");
    closeThread.start();

    // Small pause lets close() flip draining=true and reach workerPool.close().
    Thread.sleep(100);
    assertEquals(
        StreamStatus.Phase.DRAINING,
        client.status().phase(),
        "stream should be DRAINING while waiting for handler");

    handlerRelease.countDown();
    closeThread.join(TimeUnit.SECONDS.toMillis(6));
    assertFalse(closeThread.isAlive(), "close() should return promptly once handler returns");

    assertTrue(handlerExited.get(), "handler ran to completion");
    assertEquals(StreamStatus.Phase.CLOSED, client.status().phase());

    // The server should have received an Ack for the event before the stream closed.
    assertTrue(
        waitForCondition(() -> service.acked.contains("w-drain-1"), 2000),
        "server should receive Ack for in-flight event; got acks=" + service.acked);
  }

  /** No further Pulls should leave the client while draining. */
  @Test
  void drain_suppressesFurtherPulls() throws Exception {
    var handlerEntered = new CountDownLatch(1);
    var handlerRelease = new CountDownLatch(1);

    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(AgentCallEvent event) {
            handlerEntered.countDown();
            try {
              handlerRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            () -> 5,
            "test",
            "1.0",
            100,
            List.of(),
            Duration.ofSeconds(5));
    client.start(channel);

    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS));
    // Initial refill should have sent a Pull(5).
    assertTrue(
        waitForCondition(() -> service.totalPullCount() >= 5, 2000),
        "initial pull(5); got " + service.totalPullCount());

    service.sendWorkItem("w-suppress-1", WorkCategory.WORK_CATEGORY_EVENT);
    assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));

    int pullCountBeforeClose = service.totalPullCount();
    var closeThread = new Thread(client::close);
    closeThread.start();

    // Give close() time to set draining=true.
    Thread.sleep(150);

    // Release the handler. refillToTarget runs in the finally block, and must see draining=true.
    handlerRelease.countDown();
    closeThread.join(TimeUnit.SECONDS.toMillis(6));

    assertEquals(
        pullCountBeforeClose,
        service.totalPullCount(),
        "no new Pulls should be sent while draining; pre-close="
            + pullCountBeforeClose
            + " post-close="
            + service.totalPullCount());
  }

  /**
   * A stuck handler past the drain timeout should trigger workerPool.shutdownNow and unblock the
   * close() call. The close() caller should return in ~timeout wall time.
   */
  @Test
  void drain_timeoutForcesShutdownWhenHandlerWedged() throws Exception {
    var handlerEntered = new CountDownLatch(1);
    var handlerInterrupted = new AtomicBoolean(false);
    // Handler that blocks "forever" but respects interrupt.
    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(AgentCallEvent event) {
            handlerEntered.countDown();
            try {
              Thread.sleep(30_000);
            } catch (InterruptedException e) {
              handlerInterrupted.set(true);
              Thread.currentThread().interrupt();
            }
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            () -> 10,
            "test",
            "1.0",
            100,
            List.of(),
            Duration.ofMillis(500));
    client.start(channel);

    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS));
    service.sendWorkItem("w-wedged", WorkCategory.WORK_CATEGORY_EVENT);
    assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));

    long startMs = System.currentTimeMillis();
    client.close();
    long elapsedMs = System.currentTimeMillis() - startMs;

    assertTrue(
        elapsedMs < 3_000,
        "close() should return within a few seconds of the 500ms timeout; took "
            + elapsedMs
            + "ms");
    assertEquals(StreamStatus.Phase.CLOSED, client.status().phase());
    // Handler should have been interrupted by shutdownNow.
    assertTrue(
        waitForCondition(handlerInterrupted::get, 2000),
        "wedged handler should be interrupted after drain timeout");
  }

  // --- helpers ---

  private static ExileConfig dummyConfig() {
    return ExileConfig.builder()
        .rootCert("unused")
        .publicCert("unused")
        .privateKey("unused")
        .apiHostname("in-process")
        .apiPort(0)
        .build();
  }

  private static boolean waitForCondition(java.util.function.BooleanSupplier cond, long ms)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < deadline) {
      if (cond.getAsBoolean()) return true;
      Thread.sleep(10);
    }
    return cond.getAsBoolean();
  }

  /**
   * In-process WorkerService with manual WorkItem delivery and tracking of Ack messages. Same shape
   * as WorkStreamClientRefillTest's ManualWorkerService plus explicit Ack recording.
   */
  static class ShutdownWorkerService extends WorkerServiceGrpc.WorkerServiceImplBase {
    final CopyOnWriteArrayList<Integer> pullCounts = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> acked = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> resulted = new CopyOnWriteArrayList<>();
    final CountDownLatch registeredLatch = new CountDownLatch(1);
    private volatile StreamObserver<WorkResponse> activeObserver;

    int totalPullCount() {
      return pullCounts.stream().mapToInt(Integer::intValue).sum();
    }

    void sendWorkItem(String workId, WorkCategory category) {
      var obs = activeObserver;
      if (obs == null) throw new IllegalStateException("No active stream");
      var b = WorkItem.newBuilder().setWorkId(workId).setCategory(category).setAttempt(1);
      if (category == WorkCategory.WORK_CATEGORY_JOB) {
        b.setListPools(ListPoolsTask.getDefaultInstance());
      } else {
        b.setAgentCall(AgentCall.newBuilder().setCallSid(1).setAgentCallSid(1));
      }
      obs.onNext(WorkResponse.newBuilder().setWorkItem(b.build()).build());
    }

    @Override
    public StreamObserver<WorkRequest> workStream(StreamObserver<WorkResponse> responseObserver) {
      activeObserver = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(WorkRequest request) {
          if (request.hasRegister()) {
            responseObserver.onNext(
                WorkResponse.newBuilder()
                    .setRegistered(
                        Registered.newBuilder()
                            .setClientId("shutdown-" + System.nanoTime())
                            .setHeartbeatInterval(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setDefaultLease(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setMaxInflight(100))
                    .build());
            registeredLatch.countDown();
          } else if (request.hasPull()) {
            pullCounts.add(request.getPull().getMaxItems());
          } else if (request.hasAck()) {
            acked.addAll(request.getAck().getWorkIdsList());
          } else if (request.hasResult()) {
            var workId = request.getResult().getWorkId();
            resulted.add(workId);
            responseObserver.onNext(
                WorkResponse.newBuilder()
                    .setResultAccepted(ResultAccepted.newBuilder().setWorkId(workId))
                    .build());
          }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }

  // Unused helper kept for potential future wire-level assertions.
  @SuppressWarnings("unused")
  private static AtomicInteger counter() {
    return new AtomicInteger();
  }
}
