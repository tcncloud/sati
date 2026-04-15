package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.model.Pool;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the refill-to-target credit mechanism and category-aware completion recorders in
 * WorkStreamClient.
 */
class WorkStreamClientRefillTest {

  private static final String SERVER_NAME = "refill-test";

  private Server server;
  private ManagedChannel channel;
  private TestWorkerService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new TestWorkerService();
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
    if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * When capacityTarget grows from 10 to 50, the next completion should pull(40) to fill the gap.
   */
  @Test
  void refillToTarget_pullsDeltaWhenTargetGrows() throws Exception {
    var capacityRef = new AtomicInteger(10);
    IntSupplier capacityProvider = capacityRef::get;

    // Block the event handler until we release it so we can control timing.
    var handlerLatch = new CountDownLatch(1);
    var handlerCalled = new CountDownLatch(1);
    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) {
            handlerCalled.countDown();
            try {
              handlerLatch.await(5, TimeUnit.SECONDS);
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
            capacityProvider,
            "test",
            "1.0",
            100,
            List.of());

    client.start(channel);

    // Wait for registration and initial pull.
    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS), "Should register");

    // Server should have received Pull(10) for initial capacity.
    assertTrue(
        waitForCondition(() -> service.totalPullCount() >= 10, 2000),
        "Initial pull should be 10, got " + service.totalPullCount());

    // Server sends one work item; the service auto-sends on each Pull.
    // Wait for the handler to be called with the first item.
    assertTrue(handlerCalled.await(5, TimeUnit.SECONDS), "Handler should be called");

    // Clear pull records so we can track the refill delta.
    service.pullCounts.clear();

    // Grow the target before completing.
    capacityRef.set(50);

    // Release the handler — processWorkItem's finally block calls refillToTarget.
    handlerLatch.countDown();

    // refillToTarget should see target=50, outstanding=9 (10 initial - 1 received),
    // delta = 50 - 9 = 41.
    assertTrue(
        waitForCondition(() -> service.totalPullCount() >= 40, 2000),
        "Expected pull delta >= 40 after target grew, got " + service.totalPullCount());

    client.close();
  }

  /** When capacityTarget shrinks, no pulls should be issued — let in-flight drain naturally. */
  @Test
  void refillToTarget_noPullWhenTargetShrinks() throws Exception {
    // Use a service that does NOT auto-send work items — we control delivery manually.
    if (server != null) server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
    if (channel != null) channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);

    var manualService = new ManualWorkerService();
    server =
        InProcessServerBuilder.forName(SERVER_NAME + "-shrink")
            .directExecutor()
            .addService(manualService)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME + "-shrink").directExecutor().build();

    var capacityRef = new AtomicInteger(50);
    IntSupplier capacityProvider = capacityRef::get;

    var handlerLatch = new CountDownLatch(1);
    var handlerCalled = new CountDownLatch(1);
    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) {
            handlerCalled.countDown();
            try {
              handlerLatch.await(5, TimeUnit.SECONDS);
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
            capacityProvider,
            "test",
            "1.0",
            100,
            List.of());

    client.start(channel);
    assertTrue(manualService.registeredLatch.await(5, TimeUnit.SECONDS));

    // Wait for initial Pull(50) to arrive at the server.
    assertTrue(
        waitForCondition(() -> manualService.totalPullCount() >= 50, 2000),
        "Initial pull should be 50");

    // Send exactly one work item.
    manualService.sendWorkItem("w-0", WorkCategory.WORK_CATEGORY_EVENT);
    assertTrue(handlerCalled.await(5, TimeUnit.SECONDS));

    // After registration: outstanding = 50. One item received: outstanding = 49.
    // Now shrink target to 10, clear pull counts, and release the handler.
    manualService.pullCounts.clear();
    capacityRef.set(10);
    handlerLatch.countDown();

    // refillToTarget should see target=10, outstanding=49, delta=-39 → no pull.
    Thread.sleep(300);
    assertEquals(
        0,
        manualService.totalPullCount(),
        "No pulls when target < outstanding, but got " + manualService.totalPullCount());

    client.close();
  }

  /**
   * outstandingCredits stays consistent across a receive/complete cycle. After sending one item and
   * completing it, refillToTarget should restore credits to target.
   */
  @Test
  void outstandingCredits_consistentAcrossReceiveCompleteCycle() throws Exception {
    // Use manual service for precise control.
    if (server != null) server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
    if (channel != null) channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);

    var manualService = new ManualWorkerService();
    server =
        InProcessServerBuilder.forName(SERVER_NAME + "-credits")
            .directExecutor()
            .addService(manualService)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME + "-credits").directExecutor().build();

    int targetCapacity = 5;
    var capacityRef = new AtomicInteger(targetCapacity);
    IntSupplier capacityProvider = capacityRef::get;

    var processedLatch = new CountDownLatch(1);
    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) {
            processedLatch.countDown();
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            capacityProvider,
            "test",
            "1.0",
            100,
            List.of());

    client.start(channel);
    assertTrue(manualService.registeredLatch.await(5, TimeUnit.SECONDS));

    // Initial Pull(5) should arrive.
    assertTrue(
        waitForCondition(() -> manualService.totalPullCount() >= 5, 2000),
        "Initial pull should be 5");

    // Send one work item; outstanding goes from 5 to 4 on receive.
    manualService.pullCounts.clear();
    manualService.sendWorkItem("w-0", WorkCategory.WORK_CATEGORY_EVENT);

    // Wait for processing to complete.
    assertTrue(processedLatch.await(5, TimeUnit.SECONDS), "Item should be processed");

    // After completion, refillToTarget sees target=5, outstanding=4, delta=1 → Pull(1).
    assertTrue(
        waitForCondition(() -> manualService.totalPullCount() >= 1, 2000),
        "Expected refill Pull(1), got " + manualService.totalPullCount());
    assertEquals(1, manualService.totalPullCount(), "Should pull exactly 1 to refill to target");

    // Inflight should drain to 0.
    assertTrue(
        waitForCondition(() -> client.status().inflight() == 0, 2000),
        "Inflight should drain to 0");

    client.close();
  }

  /** CompletionRecorder.record(_, false) fires when the plugin handler throws. */
  @Test
  void completionRecorder_recordsFailureOnHandlerException() throws Exception {
    var capacityRef = new AtomicInteger(5);

    var completionRecords = new CopyOnWriteArrayList<boolean[]>();
    var completionLatch = new CountDownLatch(1);

    // Event handler that throws.
    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) throws Exception {
            throw new RuntimeException("handler exploded");
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            capacityRef::get,
            "test",
            "1.0",
            100,
            List.of());

    client.setEventCompletionRecorder(
        (nanos, success) -> {
          completionRecords.add(new boolean[] {success});
          completionLatch.countDown();
        });

    client.start(channel);
    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS));

    // Wait for the completion recorder to fire.
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Completion recorder should fire");

    assertFalse(completionRecords.isEmpty(), "Should have at least one completion record");
    assertFalse(completionRecords.get(0)[0], "success should be false when handler throws");

    client.close();
  }

  /** CompletionRecorder.record(_, true) fires on successful event processing. */
  @Test
  void completionRecorder_recordsSuccessOnNormalCompletion() throws Exception {
    var capacityRef = new AtomicInteger(5);

    var completionRecords = new CopyOnWriteArrayList<boolean[]>();
    var completionLatch = new CountDownLatch(1);

    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) {
            // success
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            capacityRef::get,
            "test",
            "1.0",
            100,
            List.of());

    client.setEventCompletionRecorder(
        (nanos, success) -> {
          completionRecords.add(new boolean[] {success});
          completionLatch.countDown();
        });

    client.start(channel);
    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS));
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS));

    assertFalse(completionRecords.isEmpty());
    assertTrue(completionRecords.get(0)[0], "success should be true on normal completion");

    client.close();
  }

  /** Job completion recorder fires for job work items, not event recorder. */
  @Test
  void completionRecorder_routesToJobRecorderForJobs() throws Exception {
    // Use a job-sending service.
    if (server != null) server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
    if (channel != null) channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);

    var jobService = new TestWorkerService(WorkCategory.WORK_CATEGORY_JOB);
    server =
        InProcessServerBuilder.forName(SERVER_NAME + "-job")
            .directExecutor()
            .addService(jobService)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME + "-job").directExecutor().build();

    var capacityRef = new AtomicInteger(5);
    var jobRecords = new CopyOnWriteArrayList<Long>();
    var eventRecords = new CopyOnWriteArrayList<Long>();
    var jobLatch = new CountDownLatch(1);

    JobHandler jobHandler =
        new JobHandler() {
          @Override
          public List<Pool> listPools() {
            return Collections.emptyList();
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            jobHandler,
            new EventHandler() {},
            capacityRef::get,
            "test",
            "1.0",
            100,
            List.of());

    client.setJobCompletionRecorder(
        (nanos, success) -> {
          jobRecords.add(nanos);
          jobLatch.countDown();
        });
    client.setEventCompletionRecorder(
        (nanos, success) -> {
          eventRecords.add(nanos);
        });

    client.start(channel);
    assertTrue(jobService.registeredLatch.await(5, TimeUnit.SECONDS));
    assertTrue(jobLatch.await(5, TimeUnit.SECONDS));

    assertFalse(jobRecords.isEmpty(), "Job recorder should fire");
    assertTrue(eventRecords.isEmpty(), "Event recorder should NOT fire for jobs");

    client.close();
  }

  /**
   * Backward compat: durationRecorder still fires for both jobs and events when category-specific
   * recorders are also set.
   */
  @Test
  void durationRecorder_stillFiresForAllCompletions() throws Exception {
    var capacityRef = new AtomicInteger(5);
    var durationRecords = new CopyOnWriteArrayList<Double>();
    var durationLatch = new CountDownLatch(1);

    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) {
            // success
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            capacityRef::get,
            "test",
            "1.0",
            100,
            List.of());

    client.setDurationRecorder(
        d -> {
          durationRecords.add(d);
          durationLatch.countDown();
        });
    client.setEventDurationRecorder(d -> {}); // also set category recorder

    client.start(channel);
    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS));
    assertTrue(durationLatch.await(5, TimeUnit.SECONDS));

    assertFalse(durationRecords.isEmpty(), "durationRecorder should still fire");
    assertTrue(durationRecords.get(0) >= 0, "Duration should be non-negative");

    client.close();
  }

  /**
   * Event duration recorder fires with correct category routing alongside the unified
   * durationRecorder.
   */
  @Test
  void eventDurationRecorder_firesForEvents() throws Exception {
    var capacityRef = new AtomicInteger(5);
    var eventDurations = new CopyOnWriteArrayList<Double>();
    var unifiedDurations = new CopyOnWriteArrayList<Double>();
    var latch = new CountDownLatch(1);

    EventHandler eventHandler =
        new EventHandler() {
          @Override
          public void onAgentCall(com.tcn.exile.model.event.AgentCallEvent event) {
            // success
          }
        };

    var client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {},
            eventHandler,
            capacityRef::get,
            "test",
            "1.0",
            100,
            List.of());

    client.setDurationRecorder(unifiedDurations::add);
    client.setEventDurationRecorder(
        d -> {
          eventDurations.add(d);
          latch.countDown();
        });

    client.start(channel);
    assertTrue(service.registeredLatch.await(5, TimeUnit.SECONDS));
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertFalse(eventDurations.isEmpty(), "Event duration recorder should fire");
    assertFalse(unifiedDurations.isEmpty(), "Unified recorder should also fire");

    client.close();
  }

  // --- Helpers ---

  /**
   * Dummy config that satisfies non-null constraints. Never used for real TLS since tests inject an
   * in-process channel via start(ManagedChannel).
   */
  private static ExileConfig dummyConfig() {
    return ExileConfig.builder()
        .rootCert("unused")
        .publicCert("unused")
        .privateKey("unused")
        .apiHostname("in-process")
        .apiPort(0)
        .build();
  }

  private static boolean waitForCondition(java.util.function.BooleanSupplier condition, long ms)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(10);
    }
    return condition.getAsBoolean();
  }

  /**
   * In-process WorkerService that tracks Pull counts and sends WorkItems. Each Pull generates one
   * work item response.
   */
  static class TestWorkerService extends WorkerServiceGrpc.WorkerServiceImplBase {
    final CopyOnWriteArrayList<Integer> pullCounts = new CopyOnWriteArrayList<>();
    final CountDownLatch registeredLatch = new CountDownLatch(1);
    private final WorkCategory workCategory;

    TestWorkerService() {
      this(WorkCategory.WORK_CATEGORY_EVENT);
    }

    TestWorkerService(WorkCategory category) {
      this.workCategory = category;
    }

    int totalPullCount() {
      return pullCounts.stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public StreamObserver<WorkRequest> workStream(StreamObserver<WorkResponse> responseObserver) {
      return new StreamObserver<>() {
        int seq = 0;

        @Override
        public void onNext(WorkRequest request) {
          if (request.hasRegister()) {
            responseObserver.onNext(
                WorkResponse.newBuilder()
                    .setRegistered(
                        Registered.newBuilder()
                            .setClientId("test-" + System.nanoTime())
                            .setHeartbeatInterval(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setDefaultLease(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setMaxInflight(100))
                    .build());
            registeredLatch.countDown();
          } else if (request.hasPull()) {
            int count = request.getPull().getMaxItems();
            pullCounts.add(count);
            // Send one work item for each pull count.
            for (int i = 0; i < count; i++) {
              int id = seq++;
              var itemBuilder =
                  WorkItem.newBuilder()
                      .setWorkId("w-" + id)
                      .setCategory(workCategory)
                      .setAttempt(1);
              if (workCategory == WorkCategory.WORK_CATEGORY_JOB) {
                itemBuilder.setListPools(ListPoolsTask.getDefaultInstance());
              } else {
                itemBuilder.setAgentCall(AgentCall.newBuilder().setCallSid(id).setAgentCallSid(id));
              }
              responseObserver.onNext(
                  WorkResponse.newBuilder().setWorkItem(itemBuilder.build()).build());
            }
          } else if (request.hasResult()) {
            responseObserver.onNext(
                WorkResponse.newBuilder()
                    .setResultAccepted(
                        ResultAccepted.newBuilder().setWorkId(request.getResult().getWorkId()))
                    .build());
          }
          // ACK/NACK — no response needed.
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

  /**
   * WorkerService that records Pulls but does NOT auto-send work items. Tests call {@link
   * #sendWorkItem} to inject items manually, giving full control over timing.
   */
  static class ManualWorkerService extends WorkerServiceGrpc.WorkerServiceImplBase {
    final CopyOnWriteArrayList<Integer> pullCounts = new CopyOnWriteArrayList<>();
    final CountDownLatch registeredLatch = new CountDownLatch(1);
    private volatile StreamObserver<WorkResponse> activeObserver;

    int totalPullCount() {
      return pullCounts.stream().mapToInt(Integer::intValue).sum();
    }

    void sendWorkItem(String workId, WorkCategory category) {
      var obs = activeObserver;
      if (obs == null) throw new IllegalStateException("No active stream");
      var itemBuilder = WorkItem.newBuilder().setWorkId(workId).setCategory(category).setAttempt(1);
      if (category == WorkCategory.WORK_CATEGORY_JOB) {
        itemBuilder.setListPools(ListPoolsTask.getDefaultInstance());
      } else {
        itemBuilder.setAgentCall(AgentCall.newBuilder().setCallSid(1).setAgentCallSid(1));
      }
      obs.onNext(WorkResponse.newBuilder().setWorkItem(itemBuilder.build()).build());
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
                            .setClientId("manual-" + System.nanoTime())
                            .setHeartbeatInterval(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setDefaultLease(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setMaxInflight(100))
                    .build());
            registeredLatch.countDown();
          } else if (request.hasPull()) {
            pullCounts.add(request.getPull().getMaxItems());
            // Do NOT send work items — let the test control delivery.
          } else if (request.hasResult()) {
            responseObserver.onNext(
                WorkResponse.newBuilder()
                    .setResultAccepted(
                        ResultAccepted.newBuilder().setWorkId(request.getResult().getWorkId()))
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
}
