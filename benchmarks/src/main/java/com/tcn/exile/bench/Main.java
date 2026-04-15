package com.tcn.exile.bench;

import com.tcn.exile.ExileClient;
import com.tcn.exile.ExileConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benchmark runner entry point.
 *
 * <p>Usage:
 *
 * <pre>
 *   ./gradlew :benchmarks:run --args="\
 *     --certs=/tmp/sati-benchmark/certs \
 *     --grpc-host=localhost --grpc-port=50051 \
 *     --control-url=http://localhost:50052 \
 *     --duration=60 \
 *     --reports=/path/to/output \
 *     [--scenario=burst-events-10k]        (default: run all)
 *     [--max-concurrency=5]                (sati default)
 *     [--job-latency-ms=10]                (plugin base latency per job)
 *     [--event-latency-ms=5]               (plugin base latency per event)
 *     [--plugin-error-rate=0.0]            (0..1, probability of throw per item)
 * </pre>
 */
public final class Main {
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    Args a = Args.parse(args);

    // Build plugin, config, client.
    BenchmarkPlugin plugin =
        new BenchmarkPlugin(
            a.jobLatencyMs * 1_000_000L,
            a.jobJitterMs * 1_000_000L,
            a.eventLatencyMs * 1_000_000L,
            a.eventJitterMs * 1_000_000L,
            a.pluginErrorRate);

    ExileConfig config =
        ExileConfig.builder()
            .rootCert(Files.readString(a.certsDir.resolve("ca.crt")))
            .publicCert(Files.readString(a.certsDir.resolve("client.crt")))
            .privateKey(Files.readString(a.certsDir.resolve("client.key")))
            .apiHostname(a.grpcHost)
            .apiPort(a.grpcPort)
            .certificateName("bench-client")
            .build();

    ControlClient control = new ControlClient(a.controlUrl);
    if (!control.waitHealthy(Duration.ofSeconds(30))) {
      throw new IllegalStateException("control server not healthy at " + a.controlUrl);
    }

    log.info("Connecting to gate at {}:{}", a.grpcHost, a.grpcPort);
    ExileClient client =
        ExileClient.builder()
            .config(config)
            .plugin(plugin)
            .clientName("sati-benchmark")
            .clientVersion("0.0.0")
            .maxConcurrency(a.maxConcurrency)
            .build();

    Report report = new Report();
    report.putMetadata("timestamp", Instant.now().toString());
    report.putMetadata("grpc_host", a.grpcHost + ":" + a.grpcPort);
    report.putMetadata("max_concurrency", a.maxConcurrency);
    report.putMetadata("duration_s", a.durationSeconds);
    report.putMetadata("job_latency_ms", a.jobLatencyMs);
    report.putMetadata("event_latency_ms", a.eventLatencyMs);
    report.putMetadata("plugin_error_rate", a.pluginErrorRate);

    try {
      client.start();
      // Wait for config poll + WorkStream to reach ACTIVE (client polls every 10 s; warm up 12 s).
      log.info("Warming up (waiting for WorkStream to become ACTIVE)...");
      long warmDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < warmDeadline) {
        var status = client.streamStatus();
        if (status != null && status.phase() != null && status.phase().name().equals("ACTIVE")) {
          log.info(
              "WorkStream ACTIVE after {} ms",
              (System.nanoTime() - (warmDeadline - Duration.ofSeconds(20).toNanos())) / 1_000_000);
          break;
        }
        Thread.sleep(200);
      }

      List<String> toRun = a.scenario.equals("all") ? Scenarios.names() : List.of(a.scenario);
      for (String name : toRun) {
        Scenarios.Scenario s = Scenarios.ALL.get(name);
        if (s == null) {
          log.warn("Unknown scenario '{}' — skipping", name);
          continue;
        }
        log.info("=== scenario: {} ===", name);
        plugin.jobLatency.reset();
        plugin.eventLatency.reset();
        Report.Scenario rs = report.scenario(name);
        long startNs = System.nanoTime();
        s.run(
            new Scenarios.Context(
                plugin, client, control, Duration.ofSeconds(a.durationSeconds), rs));
        long elapsedNs = System.nanoTime() - startNs;
        finalizeScenario(rs, plugin, control, elapsedNs);
        log.info(
            "  jobs/s={}  events/s={}  job_p95={}ms  event_p95={}ms",
            String.format(Locale.ROOT, "%.1f", rs.jobsPerSec),
            String.format(Locale.ROOT, "%.1f", rs.eventsPerSec),
            rs.jobLatencyMsP50P95P99Max[1],
            rs.eventLatencyMsP50P95P99Max[1]);
        client
            .adaptiveSnapshot()
            .ifPresent(
                snap -> {
                  log.info(
                      "  adaptive: limit={} ceiling={} p95={}ms ema={}ms min={}ms"
                          + "  sloG={} minG={} resG={} errors={}",
                      snap.limit(),
                      snap.effectiveCeiling(),
                      snap.jobP95Millis(),
                      snap.jobEmaMillis(),
                      snap.decayingMinMillis(),
                      String.format(Locale.ROOT, "%.2f", snap.sloGradient()),
                      String.format(Locale.ROOT, "%.2f", snap.minGradient()),
                      String.format(Locale.ROOT, "%.2f", snap.resourceGradient()),
                      snap.errorCount());
                  rs.adaptive =
                      java.util.Map.of(
                          "limit", (Object) snap.limit(),
                          "effective_ceiling", (Object) snap.effectiveCeiling(),
                          "job_p95_ms", (Object) snap.jobP95Millis(),
                          "job_ema_ms", (Object) snap.jobEmaMillis(),
                          "decaying_min_ms", (Object) snap.decayingMinMillis(),
                          "slo_gradient", (Object) snap.sloGradient(),
                          "min_gradient", (Object) snap.minGradient(),
                          "resource_gradient", (Object) snap.resourceGradient(),
                          "errors", (Object) snap.errorCount());
                });
      }
    } finally {
      client.close();
    }

    // Write report.
    String ts =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(Instant.now().atZone(java.time.ZoneId.systemDefault()));
    Path outPath = a.reportsDir.resolve(ts + ".json");
    report.writeTo(outPath);
    System.out.println();
    System.out.println("=== report ===");
    System.out.println(report.renderJson());
    System.out.println("Written to: " + outPath);
  }

  private static void finalizeScenario(
      Report.Scenario rs, BenchmarkPlugin plugin, ControlClient control, long elapsedNs) {
    rs.durationSeconds = elapsedNs / 1_000_000_000.0;
    rs.totalJobs = plugin.jobLatency.count();
    rs.totalEvents = plugin.eventLatency.count();
    rs.jobsPerSec = rs.totalJobs / Math.max(rs.durationSeconds, 1e-9);
    rs.eventsPerSec = rs.totalEvents / Math.max(rs.durationSeconds, 1e-9);
    rs.jobLatencyMsP50P95P99Max = nanosToMs(plugin.jobLatency.percentiles());
    rs.eventLatencyMsP50P95P99Max = nanosToMs(plugin.eventLatency.percentiles());
    try {
      rs.serverStats = new LinkedHashMap<>(control.stats());
    } catch (Exception e) {
      log.warn("failed to fetch server stats: {}", e.getMessage());
    }
  }

  private static long[] nanosToMs(long[] ns) {
    long[] out = new long[ns.length];
    for (int i = 0; i < ns.length; i++) out[i] = ns[i] / 1_000_000L;
    return out;
  }

  static final class Args {
    Path certsDir = Path.of("/tmp/sati-benchmark/certs");
    String grpcHost = "localhost";
    int grpcPort = 50051;
    String controlUrl = "http://localhost:50052";
    int durationSeconds = 60;
    Path reportsDir = Path.of("./benchmarks/results");
    String scenario = "all";
    int maxConcurrency = 5;
    long jobLatencyMs = 10;
    long jobJitterMs = 5;
    long eventLatencyMs = 5;
    long eventJitterMs = 2;
    double pluginErrorRate = 0.0;

    static Args parse(String[] args) {
      Args a = new Args();
      List<String> errs = new ArrayList<>();
      for (String arg : args) {
        if (!arg.startsWith("--")) continue;
        int eq = arg.indexOf('=');
        if (eq < 0) continue;
        String k = arg.substring(2, eq);
        String v = arg.substring(eq + 1);
        try {
          switch (k) {
            case "certs" -> a.certsDir = Path.of(v);
            case "grpc-host" -> a.grpcHost = v;
            case "grpc-port" -> a.grpcPort = Integer.parseInt(v);
            case "control-url" -> a.controlUrl = v;
            case "duration" -> a.durationSeconds = Integer.parseInt(v);
            case "reports" -> a.reportsDir = Path.of(v);
            case "scenario" -> a.scenario = v;
            case "max-concurrency" -> a.maxConcurrency = Integer.parseInt(v);
            case "job-latency-ms" -> a.jobLatencyMs = Long.parseLong(v);
            case "job-jitter-ms" -> a.jobJitterMs = Long.parseLong(v);
            case "event-latency-ms" -> a.eventLatencyMs = Long.parseLong(v);
            case "event-jitter-ms" -> a.eventJitterMs = Long.parseLong(v);
            case "plugin-error-rate" -> a.pluginErrorRate = Double.parseDouble(v);
            default -> errs.add("unknown flag: " + k);
          }
        } catch (NumberFormatException nfe) {
          errs.add("bad numeric value for --" + k + "=" + v);
        }
      }
      if (!errs.isEmpty()) {
        throw new IllegalArgumentException("bad args: " + String.join(", ", errs));
      }
      return a;
    }
  }
}
