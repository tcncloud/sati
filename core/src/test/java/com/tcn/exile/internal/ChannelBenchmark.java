package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Benchmarks comparing single reused channel vs new channel per stream. Uses in-process transport
 * to isolate the channel/stream overhead from network latency.
 */
class ChannelBenchmark {

  private static final String SERVER_NAME = "benchmark-server";
  private Server server;

  @BeforeEach
  void setUp() throws Exception {
    server =
        InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(new BenchmarkWorkerService())
            .build()
            .start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void benchmarkReusedChannel() throws Exception {
    int iterations = 100;
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();

    var times = new ArrayList<Long>();
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      runSingleStream(channel);
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);
    }

    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;
    long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
    long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
    long p50 = times.stream().sorted().skip(iterations / 2).findFirst().orElse(0L);
    long p99 = times.stream().sorted().skip((long) (iterations * 0.99)).findFirst().orElse(0L);

    System.out.println("=== REUSED CHANNEL (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.2f ms)%n", avg, avg / 1_000_000.0);
    System.out.printf("  min:  %,d ns  (%.2f ms)%n", min, min / 1_000_000.0);
    System.out.printf("  max:  %,d ns  (%.2f ms)%n", max, max / 1_000_000.0);
    System.out.printf("  p50:  %,d ns  (%.2f ms)%n", p50, p50 / 1_000_000.0);
    System.out.printf("  p99:  %,d ns  (%.2f ms)%n", p99, p99 / 1_000_000.0);
  }

  @Test
  void benchmarkNewChannelPerStream() throws Exception {
    int iterations = 100;

    var times = new ArrayList<Long>();
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
      runSingleStream(channel);
      channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);
    }

    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;
    long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
    long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
    long p50 = times.stream().sorted().skip(iterations / 2).findFirst().orElse(0L);
    long p99 = times.stream().sorted().skip((long) (iterations * 0.99)).findFirst().orElse(0L);

    System.out.println("=== NEW CHANNEL PER STREAM (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.2f ms)%n", avg, avg / 1_000_000.0);
    System.out.printf("  min:  %,d ns  (%.2f ms)%n", min, min / 1_000_000.0);
    System.out.printf("  max:  %,d ns  (%.2f ms)%n", max, max / 1_000_000.0);
    System.out.printf("  p50:  %,d ns  (%.2f ms)%n", p50, p50 / 1_000_000.0);
    System.out.printf("  p99:  %,d ns  (%.2f ms)%n", p99, p99 / 1_000_000.0);
  }

  @Test
  void benchmarkConcurrentStreamsOnSingleChannel() throws Exception {
    int concurrency = 10;
    int streamsPerThread = 50;
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();

    var totalStreams = new AtomicInteger(0);
    long start = System.nanoTime();

    var threads = new ArrayList<Thread>();
    for (int t = 0; t < concurrency; t++) {
      var thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int i = 0; i < streamsPerThread; i++) {
                      try {
                        runSingleStream(channel);
                        totalStreams.incrementAndGet();
                      } catch (Exception e) {
                        e.printStackTrace();
                      }
                    }
                  });
      threads.add(thread);
    }
    for (var thread : threads) thread.join();

    long elapsed = System.nanoTime() - start;
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    System.out.println(
        "=== CONCURRENT STREAMS (" + concurrency + " threads x " + streamsPerThread + ") ===");
    System.out.printf("  total streams: %d%n", totalStreams.get());
    System.out.printf("  total time:    %.2f ms%n", elapsed / 1_000_000.0);
    System.out.printf("  per stream:    %.2f ms%n", (elapsed / 1_000_000.0) / totalStreams.get());
    System.out.printf(
        "  throughput:    %.0f streams/sec%n", totalStreams.get() / (elapsed / 1_000_000_000.0));
  }

  /**
   * Opens a bidirectional WorkStream, sends Register + Pull, receives one WorkItem response, sends
   * an Ack, and closes. Simulates one reconnect cycle.
   */
  private void runSingleStream(ManagedChannel channel) throws Exception {
    var stub = WorkerServiceGrpc.newStub(channel);
    var latch = new CountDownLatch(1);
    var received = new AtomicInteger(0);

    var observer =
        stub.workStream(
            new StreamObserver<WorkResponse>() {
              @Override
              public void onNext(WorkResponse response) {
                received.incrementAndGet();
                if (response.hasRegistered()) {
                  // Got Registered — done.
                  latch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                latch.countDown();
              }

              @Override
              public void onCompleted() {
                latch.countDown();
              }
            });

    // Send Register.
    observer.onNext(
        WorkRequest.newBuilder()
            .setRegister(Register.newBuilder().setClientName("benchmark").setClientVersion("1.0"))
            .build());

    // Wait for Registered response.
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for Registered");
    assertTrue(received.get() >= 1, "Expected at least 1 response");

    // Close stream gracefully.
    observer.onCompleted();
  }

  @Test
  void benchmarkMessageThroughput() throws Exception {
    int messageCount = 10_000;
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = WorkerServiceGrpc.newStub(channel);
    var received = new AtomicInteger(0);
    var allReceived = new CountDownLatch(1);

    var observer =
        stub.workStream(
            new StreamObserver<WorkResponse>() {
              @Override
              public void onNext(WorkResponse response) {
                if (received.incrementAndGet() >= messageCount + 1) { // +1 for Registered
                  allReceived.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                allReceived.countDown();
              }

              @Override
              public void onCompleted() {
                allReceived.countDown();
              }
            });

    // Register first.
    observer.onNext(
        WorkRequest.newBuilder()
            .setRegister(Register.newBuilder().setClientName("bench").setClientVersion("1.0"))
            .build());

    // Now send Pull messages as fast as possible — server responds with WorkItem for each.
    long start = System.nanoTime();
    for (int i = 0; i < messageCount; i++) {
      observer.onNext(WorkRequest.newBuilder().setPull(Pull.newBuilder().setMaxItems(1)).build());
    }

    assertTrue(allReceived.await(10, TimeUnit.SECONDS), "Timed out waiting for all messages");
    long elapsed = System.nanoTime() - start;

    observer.onCompleted();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    double elapsedMs = elapsed / 1_000_000.0;
    double msgsPerSec = messageCount / (elapsed / 1_000_000_000.0);
    double usPerMsg = (elapsed / 1_000.0) / messageCount;

    System.out.println("=== MESSAGE THROUGHPUT (" + messageCount + " messages) ===");
    System.out.printf("  total time:    %.2f ms%n", elapsedMs);
    System.out.printf("  msgs/sec:      %,.0f%n", msgsPerSec);
    System.out.printf("  us/msg:        %.2f%n", usPerMsg);
    System.out.printf("  received:      %d%n", received.get());
  }

  @Test
  void benchmarkRoundTripLatency() throws Exception {
    int iterations = 1000;
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = WorkerServiceGrpc.newStub(channel);
    var times = new ArrayList<Long>();

    for (int i = 0; i < iterations; i++) {
      var latch = new CountDownLatch(1);
      var responseObserver =
          stub.workStream(
              new StreamObserver<WorkResponse>() {
                boolean registered = false;

                @Override
                public void onNext(WorkResponse response) {
                  if (!registered) {
                    registered = true;
                    return; // skip Registered
                  }
                  latch.countDown(); // got the WorkItem response
                }

                @Override
                public void onError(Throwable t) {
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  latch.countDown();
                }
              });

      // Register.
      responseObserver.onNext(
          WorkRequest.newBuilder()
              .setRegister(Register.newBuilder().setClientName("bench").setClientVersion("1.0"))
              .build());

      // Measure round-trip: send Pull → receive WorkItem.
      long start = System.nanoTime();
      responseObserver.onNext(
          WorkRequest.newBuilder().setPull(Pull.newBuilder().setMaxItems(1)).build());
      latch.await(5, TimeUnit.SECONDS);
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);

      responseObserver.onCompleted();
    }

    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;
    long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
    long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
    long p50 = times.stream().sorted().skip(iterations / 2).findFirst().orElse(0L);
    long p99 = times.stream().sorted().skip((long) (iterations * 0.99)).findFirst().orElse(0L);

    System.out.println("=== ROUND-TRIP LATENCY (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.3f ms)%n", avg, avg / 1_000_000.0);
    System.out.printf("  min:  %,d ns  (%.3f ms)%n", min, min / 1_000_000.0);
    System.out.printf("  max:  %,d ns  (%.3f ms)%n", max, max / 1_000_000.0);
    System.out.printf("  p50:  %,d ns  (%.3f ms)%n", p50, p50 / 1_000_000.0);
    System.out.printf("  p99:  %,d ns  (%.3f ms)%n", p99, p99 / 1_000_000.0);
  }

  /** WorkerService that responds to Register with Registered and to Pull with a WorkItem. */
  static class BenchmarkWorkerService extends WorkerServiceGrpc.WorkerServiceImplBase {
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
                            .setClientId("bench-" + System.nanoTime())
                            .setHeartbeatInterval(
                                com.google.protobuf.Duration.newBuilder().setSeconds(30))
                            .setDefaultLease(
                                com.google.protobuf.Duration.newBuilder().setSeconds(300))
                            .setMaxInflight(20))
                    .build());
          } else if (request.hasPull()) {
            // Respond with a WorkItem for each Pull.
            responseObserver.onNext(
                WorkResponse.newBuilder()
                    .setWorkItem(
                        WorkItem.newBuilder()
                            .setWorkId("w-" + (seq++))
                            .setCategory(WorkCategory.WORK_CATEGORY_EVENT)
                            .setAttempt(1)
                            .setAgentCall(
                                build.buf.gen.tcnapi.exile.gate.v3.AgentCall.newBuilder()
                                    .setCallSid(seq)
                                    .setAgentCallSid(seq)))
                    .build());
          } else if (request.hasResult() || request.hasAck()) {
            // Accept results/acks silently.
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
