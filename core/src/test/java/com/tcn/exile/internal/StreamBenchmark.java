package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Benchmarks using the BenchmarkService proto for streaming throughput and ping latency. Uses an
 * in-process server that implements BenchmarkService to isolate gRPC overhead.
 */
class StreamBenchmark {

  private static final String SERVER_NAME = "stream-benchmark";
  private Server server;

  @BeforeEach
  void setUp() throws Exception {
    server =
        InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(new InProcessBenchmarkService())
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
  void benchmarkUnlimitedThroughput() throws Exception {
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = BenchmarkServiceGrpc.newStub(channel);

    int maxMessages = 100_000;
    var received = new AtomicLong(0);
    var statsRef = new AtomicReference<BenchmarkStats>();
    var done = new CountDownLatch(1);

    var requestObserver =
        stub.streamBenchmark(
            new StreamObserver<>() {
              @Override
              public void onNext(BenchmarkResponse response) {
                if (response.hasMessage()) {
                  received.incrementAndGet();
                } else if (response.hasStats()) {
                  statsRef.set(response.getStats());
                  done.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                t.printStackTrace();
                done.countDown();
              }

              @Override
              public void onCompleted() {
                done.countDown();
              }
            });

    // Start with no flow control, minimal payload, limited messages.
    requestObserver.onNext(
        BenchmarkRequest.newBuilder()
            .setStart(
                StartBenchmark.newBuilder().setPayloadSize(0).setMaxMessages(maxMessages).setBatchSize(0))
            .build());

    assertTrue(done.await(30, TimeUnit.SECONDS), "Timed out waiting for benchmark to complete");

    var stats = statsRef.get();
    assertNotNull(stats, "Should have received stats");

    System.out.println("=== UNLIMITED THROUGHPUT (" + maxMessages + " messages, 0 byte payload) ===");
    System.out.printf("  server msgs sent:    %,d%n", stats.getTotalMessages());
    System.out.printf("  client msgs received: %,d%n", received.get());
    System.out.printf("  duration:            %,d ms%n", stats.getDurationMs());
    System.out.printf("  msgs/sec (server):   %,.0f%n", stats.getMessagesPerSecond());
    System.out.printf("  MB/sec (server):     %.2f%n", stats.getMegabytesPerSecond());

    requestObserver.onCompleted();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void benchmarkWithPayload() throws Exception {
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = BenchmarkServiceGrpc.newStub(channel);

    int maxMessages = 50_000;
    int payloadSize = 1024; // 1KB per message
    var received = new AtomicLong(0);
    var statsRef = new AtomicReference<BenchmarkStats>();
    var done = new CountDownLatch(1);

    var requestObserver =
        stub.streamBenchmark(
            new StreamObserver<>() {
              @Override
              public void onNext(BenchmarkResponse response) {
                if (response.hasMessage()) {
                  received.incrementAndGet();
                } else if (response.hasStats()) {
                  statsRef.set(response.getStats());
                  done.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                t.printStackTrace();
                done.countDown();
              }

              @Override
              public void onCompleted() {
                done.countDown();
              }
            });

    requestObserver.onNext(
        BenchmarkRequest.newBuilder()
            .setStart(
                StartBenchmark.newBuilder()
                    .setPayloadSize(payloadSize)
                    .setMaxMessages(maxMessages)
                    .setBatchSize(0))
            .build());

    assertTrue(done.await(30, TimeUnit.SECONDS), "Timed out");

    var stats = statsRef.get();
    assertNotNull(stats);

    System.out.println(
        "=== THROUGHPUT WITH PAYLOAD ("
            + maxMessages
            + " messages, "
            + payloadSize
            + " byte payload) ===");
    System.out.printf("  server msgs sent:    %,d%n", stats.getTotalMessages());
    System.out.printf("  client msgs received: %,d%n", received.get());
    System.out.printf("  duration:            %,d ms%n", stats.getDurationMs());
    System.out.printf("  msgs/sec (server):   %,.0f%n", stats.getMessagesPerSecond());
    System.out.printf("  MB/sec (server):     %.2f%n", stats.getMegabytesPerSecond());
    System.out.printf("  total bytes:         %,d%n", stats.getTotalBytes());

    requestObserver.onCompleted();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void benchmarkFlowControlled() throws Exception {
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = BenchmarkServiceGrpc.newStub(channel);

    int maxMessages = 100_000;
    int batchSize = 100;
    var received = new AtomicLong(0);
    var statsRef = new AtomicReference<BenchmarkStats>();
    var done = new CountDownLatch(1);
    var senderRef = new AtomicReference<StreamObserver<BenchmarkRequest>>();

    var requestObserver =
        stub.streamBenchmark(
            new StreamObserver<>() {
              @Override
              public void onNext(BenchmarkResponse response) {
                if (response.hasMessage()) {
                  long count = received.incrementAndGet();
                  // Send ack every batchSize messages — drives the next batch immediately.
                  if (count % batchSize == 0) {
                    senderRef
                        .get()
                        .onNext(
                            BenchmarkRequest.newBuilder()
                                .setAck(BenchmarkAck.newBuilder().setCount(batchSize))
                                .build());
                  }
                } else if (response.hasStats()) {
                  statsRef.set(response.getStats());
                  done.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                t.printStackTrace();
                done.countDown();
              }

              @Override
              public void onCompleted() {
                done.countDown();
              }
            });

    senderRef.set(requestObserver);

    requestObserver.onNext(
        BenchmarkRequest.newBuilder()
            .setStart(
                StartBenchmark.newBuilder()
                    .setPayloadSize(0)
                    .setMaxMessages(maxMessages)
                    .setBatchSize(batchSize))
            .build());

    assertTrue(done.await(30, TimeUnit.SECONDS), "Timed out waiting for flow-controlled benchmark");

    var stats = statsRef.get();
    assertNotNull(stats);

    System.out.println(
        "=== FLOW-CONTROLLED ("
            + maxMessages
            + " messages, batch_size="
            + batchSize
            + ") ===");
    System.out.printf("  server msgs sent:    %,d%n", stats.getTotalMessages());
    System.out.printf("  client msgs received: %,d%n", received.get());
    System.out.printf("  duration:            %,d ms%n", stats.getDurationMs());
    System.out.printf("  msgs/sec (server):   %,.0f%n", stats.getMessagesPerSecond());

    requestObserver.onCompleted();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void benchmarkPingLatency() throws Exception {
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = BenchmarkServiceGrpc.newBlockingStub(channel);

    int iterations = 10_000;
    var times = new ArrayList<Long>(iterations);

    // Warmup.
    for (int i = 0; i < 100; i++) {
      stub.ping(PingRequest.newBuilder().build());
    }

    for (int i = 0; i < iterations; i++) {
      long now = System.nanoTime();
      var ts =
          Timestamp.newBuilder()
              .setSeconds(now / 1_000_000_000L)
              .setNanos((int) (now % 1_000_000_000L));
      long start = System.nanoTime();
      var resp = stub.ping(PingRequest.newBuilder().setClientTime(ts).build());
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);
    }

    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    times.sort(Long::compareTo);
    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;
    long min = times.getFirst();
    long max = times.getLast();
    long p50 = times.get(iterations / 2);
    long p99 = times.get((int) (iterations * 0.99));

    System.out.println("=== PING LATENCY (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.3f us)%n", avg, avg / 1_000.0);
    System.out.printf("  min:  %,d ns  (%.3f us)%n", min, min / 1_000.0);
    System.out.printf("  max:  %,d ns  (%.3f us)%n", max, max / 1_000.0);
    System.out.printf("  p50:  %,d ns  (%.3f us)%n", p50, p50 / 1_000.0);
    System.out.printf("  p99:  %,d ns  (%.3f us)%n", p99, p99 / 1_000.0);
  }

  @Test
  void benchmarkPingWithPayload() throws Exception {
    var channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    var stub = BenchmarkServiceGrpc.newBlockingStub(channel);

    int iterations = 5_000;
    byte[] payload = new byte[1024]; // 1KB payload
    var bsPayload = ByteString.copyFrom(payload);
    var times = new ArrayList<Long>(iterations);

    // Warmup.
    for (int i = 0; i < 100; i++) {
      stub.ping(PingRequest.newBuilder().setPayload(bsPayload).build());
    }

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      stub.ping(PingRequest.newBuilder().setPayload(bsPayload).build());
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);
    }

    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    times.sort(Long::compareTo);
    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;
    long min = times.getFirst();
    long max = times.getLast();
    long p50 = times.get(iterations / 2);
    long p99 = times.get((int) (iterations * 0.99));

    System.out.println("=== PING WITH 1KB PAYLOAD (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.3f us)%n", avg, avg / 1_000.0);
    System.out.printf("  min:  %,d ns  (%.3f us)%n", min, min / 1_000.0);
    System.out.printf("  max:  %,d ns  (%.3f us)%n", max, max / 1_000.0);
    System.out.printf("  p50:  %,d ns  (%.3f us)%n", p50, p50 / 1_000.0);
    System.out.printf("  p99:  %,d ns  (%.3f us)%n", p99, p99 / 1_000.0);
  }

  /**
   * In-process BenchmarkService that implements the streaming and ping protocols for local
   * benchmarking.
   */
  static class InProcessBenchmarkService
      extends BenchmarkServiceGrpc.BenchmarkServiceImplBase {

    @Override
    public StreamObserver<BenchmarkRequest> streamBenchmark(
        StreamObserver<BenchmarkResponse> responseObserver) {
      return new StreamObserver<>() {
        int payloadSize;
        long maxMessages;
        int batchSize;
        byte[] payload;
        long totalMessages;
        long totalBytes;
        long startNanos;
        boolean stopped;

        @Override
        public void onNext(BenchmarkRequest request) {
          if (request.hasStart()) {
            var start = request.getStart();
            payloadSize = start.getPayloadSize();
            maxMessages = start.getMaxMessages();
            batchSize = start.getBatchSize();
            payload = new byte[payloadSize];
            startNanos = System.nanoTime();
            stopped = false;

            if (batchSize > 0) {
              sendBatch(batchSize);
            } else {
              // Unlimited: send all at once.
              long toSend = maxMessages > 0 ? maxMessages : Long.MAX_VALUE;
              for (long i = 0; i < toSend && !stopped; i++) {
                sendOne();
              }
              sendStats();
            }
          } else if (request.hasAck()) {
            // Flow-controlled: send next batch.
            if (!stopped && (maxMessages == 0 || totalMessages < maxMessages)) {
              sendBatch(batchSize);
            }
          } else if (request.hasStop()) {
            stopped = true;
            sendStats();
          }
        }

        private void sendBatch(int count) {
          for (int i = 0; i < count && (maxMessages == 0 || totalMessages < maxMessages); i++) {
            sendOne();
          }
          if (maxMessages > 0 && totalMessages >= maxMessages) {
            sendStats();
          }
        }

        private void sendOne() {
          var now = System.nanoTime();
          responseObserver.onNext(
              BenchmarkResponse.newBuilder()
                  .setMessage(
                      BenchmarkMessage.newBuilder()
                          .setSequence(totalMessages)
                          .setSendTime(
                              Timestamp.newBuilder()
                                  .setSeconds(now / 1_000_000_000L)
                                  .setNanos((int) (now % 1_000_000_000L)))
                          .setData(ByteString.copyFrom(payload)))
                  .build());
          totalMessages++;
          totalBytes += payloadSize;
        }

        private void sendStats() {
          long elapsed = System.nanoTime() - startNanos;
          double seconds = elapsed / 1_000_000_000.0;
          double mps = seconds > 0 ? totalMessages / seconds : 0;
          double mbps = seconds > 0 ? totalBytes / seconds / (1024 * 1024) : 0;

          responseObserver.onNext(
              BenchmarkResponse.newBuilder()
                  .setStats(
                      BenchmarkStats.newBuilder()
                          .setTotalMessages(totalMessages)
                          .setTotalBytes(totalBytes)
                          .setDurationMs(elapsed / 1_000_000)
                          .setMessagesPerSecond(mps)
                          .setMegabytesPerSecond(mbps))
                  .build());
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
      long now = System.nanoTime();
      responseObserver.onNext(
          PingResponse.newBuilder()
              .setClientTime(request.getClientTime())
              .setServerTime(
                  Timestamp.newBuilder()
                      .setSeconds(now / 1_000_000_000L)
                      .setNanos((int) (now % 1_000_000_000L)))
              .setPayload(request.getPayload())
              .build());
      responseObserver.onCompleted();
    }
  }
}
