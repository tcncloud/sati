package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.tcn.exile.ExileConfig;
import io.grpc.ManagedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

/**
 * Live benchmarks against a real exile gate v3 deployment. Reads mTLS config from the standard
 * sati config file. Skipped if config is not present.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveBenchmark {

  private static final Path CONFIG_PATH =
      Path.of(System.getProperty("user.home"), "dev/tcncloud/workdir/config/com.tcn.exiles.sati.config.cfg");

  private static ManagedChannel channel;

  @BeforeAll
  static void connect() throws Exception {
    assumeTrue(Files.exists(CONFIG_PATH), "No sati config found at " + CONFIG_PATH + " — skipping live benchmarks");

    var raw = new String(Files.readAllBytes(CONFIG_PATH), StandardCharsets.UTF_8).trim();
    byte[] json;
    try {
      json = Base64.getDecoder().decode(raw);
    } catch (IllegalArgumentException e) {
      json = raw.getBytes(StandardCharsets.UTF_8);
    }
    var jsonStr = new String(json, StandardCharsets.UTF_8);

    // Minimal JSON extraction for the 4 fields we need.
    var config = ExileConfig.builder()
        .rootCert(extractJsonString(jsonStr, "ca_certificate"))
        .publicCert(extractJsonString(jsonStr, "certificate"))
        .privateKey(extractJsonString(jsonStr, "private_key"))
        .apiHostname(parseHost(extractJsonString(jsonStr, "api_endpoint")))
        .apiPort(parsePort(extractJsonString(jsonStr, "api_endpoint")))
        .build();

    channel = ChannelFactory.create(config);
    System.out.println("Connected to " + config.apiHostname() + ":" + config.apiPort());
  }

  private static String extractJsonString(String json, String key) {
    var search = "\"" + key + "\"";
    int idx = json.indexOf(search);
    if (idx < 0) return "";
    idx = json.indexOf("\"", idx + search.length() + 1); // skip colon, find opening quote
    if (idx < 0) return "";
    int start = idx + 1;
    var sb = new StringBuilder();
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '"') break;
      if (c == '\\' && i + 1 < json.length()) {
        i++;
        sb.append(switch (json.charAt(i)) {
          case 'n' -> '\n';
          case 'r' -> '\r';
          case 't' -> '\t';
          case '\\' -> '\\';
          case '"' -> '"';
          default -> json.charAt(i);
        });
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String parseHost(String endpoint) {
    if (endpoint.contains("://")) endpoint = endpoint.substring(endpoint.indexOf("://") + 3);
    if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
    int colon = endpoint.lastIndexOf(':');
    return colon > 0 ? endpoint.substring(0, colon) : endpoint;
  }

  private static int parsePort(String endpoint) {
    if (endpoint.contains("://")) endpoint = endpoint.substring(endpoint.indexOf("://") + 3);
    if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
    int colon = endpoint.lastIndexOf(':');
    if (colon > 0) {
      try { return Integer.parseInt(endpoint.substring(colon + 1)); }
      catch (NumberFormatException e) { /* fall through */ }
    }
    return 443;
  }

  @AfterAll
  static void disconnect() {
    ChannelFactory.shutdown(channel);
  }

  @Test
  @Order(1)
  void pingLatency() {
    var stub = BenchmarkServiceGrpc.newBlockingStub(channel);
    int iterations = 1_000;
    var times = new ArrayList<Long>(iterations);

    // Warmup.
    for (int i = 0; i < 20; i++) {
      stub.ping(PingRequest.newBuilder().build());
    }

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      var resp =
          stub.ping(
              PingRequest.newBuilder()
                  .setClientTime(toTimestamp(start))
                  .build());
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);
    }

    times.sort(Long::compareTo);
    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;

    System.out.println("=== LIVE PING LATENCY (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.3f ms)%n", avg, avg / 1_000_000.0);
    System.out.printf("  min:  %,d ns  (%.3f ms)%n", times.getFirst(), times.getFirst() / 1_000_000.0);
    System.out.printf("  max:  %,d ns  (%.3f ms)%n", times.getLast(), times.getLast() / 1_000_000.0);
    System.out.printf("  p50:  %,d ns  (%.3f ms)%n", times.get(iterations / 2), times.get(iterations / 2) / 1_000_000.0);
    System.out.printf("  p99:  %,d ns  (%.3f ms)%n", times.get((int)(iterations * 0.99)), times.get((int)(iterations * 0.99)) / 1_000_000.0);
  }

  @Test
  @Order(2)
  void pingWithPayload() {
    var stub = BenchmarkServiceGrpc.newBlockingStub(channel);
    int iterations = 500;
    int payloadSize = 1024;
    var payload = ByteString.copyFrom(new byte[payloadSize]);
    var times = new ArrayList<Long>(iterations);

    // Warmup.
    for (int i = 0; i < 10; i++) {
      stub.ping(PingRequest.newBuilder().setPayload(payload).build());
    }

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      stub.ping(
          PingRequest.newBuilder()
              .setClientTime(toTimestamp(start))
              .setPayload(payload)
              .build());
      long elapsed = System.nanoTime() - start;
      times.add(elapsed);
    }

    times.sort(Long::compareTo);
    long avg = times.stream().mapToLong(Long::longValue).sum() / iterations;

    System.out.println("=== LIVE PING WITH 1KB PAYLOAD (" + iterations + " iterations) ===");
    System.out.printf("  avg:  %,d ns  (%.3f ms)%n", avg, avg / 1_000_000.0);
    System.out.printf("  min:  %,d ns  (%.3f ms)%n", times.getFirst(), times.getFirst() / 1_000_000.0);
    System.out.printf("  max:  %,d ns  (%.3f ms)%n", times.getLast(), times.getLast() / 1_000_000.0);
    System.out.printf("  p50:  %,d ns  (%.3f ms)%n", times.get(iterations / 2), times.get(iterations / 2) / 1_000_000.0);
    System.out.printf("  p99:  %,d ns  (%.3f ms)%n", times.get((int)(iterations * 0.99)), times.get((int)(iterations * 0.99)) / 1_000_000.0);
  }

  @Test
  @Order(3)
  void streamThroughputMinimal() throws Exception {
    var stub = BenchmarkServiceGrpc.newStub(channel);
    int maxMessages = 10_000;

    var result = runStreamBenchmark(stub, 0, maxMessages, 0);

    System.out.println("=== LIVE STREAM THROUGHPUT (" + maxMessages + " msgs, 0B payload) ===");
    printStreamStats(result);
  }

  @Test
  @Order(4)
  void streamThroughputWithPayload() throws Exception {
    var stub = BenchmarkServiceGrpc.newStub(channel);
    int maxMessages = 1_000;
    int payloadSize = 1024;

    var result = runStreamBenchmark(stub, payloadSize, maxMessages, 0);

    System.out.println("=== LIVE STREAM THROUGHPUT (" + maxMessages + " msgs, 1KB payload) ===");
    printStreamStats(result);
  }

  @Test
  @Order(5)
  void streamFlowControlled() throws Exception {
    var stub = BenchmarkServiceGrpc.newStub(channel);
    int maxMessages = 10_000;
    int batchSize = 100;

    var result = runStreamBenchmark(stub, 0, maxMessages, batchSize);

    System.out.println("=== LIVE FLOW-CONTROLLED (" + maxMessages + " msgs, batch=" + batchSize + ") ===");
    printStreamStats(result);
  }

  private record StreamResult(BenchmarkStats stats, long clientReceived, long clientElapsedNs) {}

  private StreamResult runStreamBenchmark(
      BenchmarkServiceGrpc.BenchmarkServiceStub stub,
      int payloadSize,
      int maxMessages,
      int batchSize)
      throws Exception {

    var received = new AtomicLong(0);
    var statsRef = new AtomicReference<BenchmarkStats>();
    var done = new CountDownLatch(1);
    var senderRef = new AtomicReference<StreamObserver<BenchmarkRequest>>();

    long clientStart = System.nanoTime();

    var requestObserver =
        stub.streamBenchmark(
            new StreamObserver<>() {
              @Override
              public void onNext(BenchmarkResponse response) {
                if (response.hasMessage()) {
                  long count = received.incrementAndGet();
                  if (batchSize > 0 && count % batchSize == 0) {
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
                System.err.println("Stream error: " + t.getMessage());
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
                    .setPayloadSize(payloadSize)
                    .setMaxMessages(maxMessages)
                    .setBatchSize(batchSize))
            .build());

    assertTrue(done.await(60, TimeUnit.SECONDS), "Timed out waiting for stream benchmark");

    long clientElapsed = System.nanoTime() - clientStart;

    // Half-close client side and give the connection a moment to settle before the next test.
    try { requestObserver.onCompleted(); } catch (Exception ignored) {}
    Thread.sleep(200);

    var stats = statsRef.get();
    if (stats == null && received.get() > 0) {
      // Server sent messages but the stats frame was lost (RST_STREAM from envoy).
      // Build approximate stats from client-side measurements.
      double clientSec = clientElapsed / 1_000_000_000.0;
      stats = BenchmarkStats.newBuilder()
          .setTotalMessages(received.get())
          .setTotalBytes(received.get() * payloadSize)
          .setDurationMs(clientElapsed / 1_000_000)
          .setMessagesPerSecond(received.get() / clientSec)
          .setMegabytesPerSecond(received.get() * payloadSize / clientSec / (1024 * 1024))
          .build();
      System.err.println("(stats estimated from client side — server stats lost to RST_STREAM)");
    }
    assertNotNull(stats, "Should have received stats");

    return new StreamResult(stats, received.get(), clientElapsed);
  }

  private void printStreamStats(StreamResult r) {
    var s = r.stats();
    double clientSeconds = r.clientElapsedNs() / 1_000_000_000.0;
    double clientMps = r.clientReceived() / clientSeconds;

    System.out.printf("  server msgs sent:     %,d%n", s.getTotalMessages());
    System.out.printf("  client msgs received: %,d%n", r.clientReceived());
    System.out.printf("  server duration:      %,d ms%n", s.getDurationMs());
    System.out.printf("  client duration:      %.0f ms%n", r.clientElapsedNs() / 1_000_000.0);
    System.out.printf("  server msgs/sec:      %,.0f%n", s.getMessagesPerSecond());
    System.out.printf("  client msgs/sec:      %,.0f%n", clientMps);
    if (s.getTotalBytes() > 0) {
      System.out.printf("  server MB/sec:        %.2f%n", s.getMegabytesPerSecond());
      System.out.printf("  total bytes:          %,d%n", s.getTotalBytes());
    }
  }

  private static Timestamp toTimestamp(long nanos) {
    return Timestamp.newBuilder()
        .setSeconds(nanos / 1_000_000_000L)
        .setNanos((int) (nanos % 1_000_000_000L))
        .build();
  }
}
