package com.tcn.exile.bench;

import com.tcn.exile.ExileClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scenario definitions — what load to drive during a benchmark run. */
final class Scenarios {
  private static final Logger log = LoggerFactory.getLogger(Scenarios.class);

  static final Map<String, Scenario> ALL =
      Map.of(
          "burst-events-10k", Scenarios::burstEvents10k,
          "sustained-jobs-100rps", Scenarios::sustainedJobs100rps,
          "mixed-steady", Scenarios::mixedSteady,
          "events-ramp-200rps", Scenarios::eventsRamp200rps);

  static List<String> names() {
    return ALL.keySet().stream().sorted().toList();
  }

  interface Scenario {
    void run(Context ctx) throws Exception;
  }

  record Context(
      BenchmarkPlugin plugin,
      ExileClient client,
      ControlClient control,
      Duration duration,
      Report.Scenario report) {}

  // --- Scenarios ---

  /**
   * 10,000 events pushed at up to 1000 rps. Server must drain them through the event poller;
   * measures event-throughput ceiling.
   */
  private static void burstEvents10k(Context ctx) throws Exception {
    ctx.control.reset();
    ctx.control.setEventRate(1000);
    drive(ctx, () -> false); // run until duration elapses
    ctx.control.setEventRate(0);
  }

  /** 100 jobs/sec sustained, no events. Measures job RTT and concurrency. */
  private static void sustainedJobs100rps(Context ctx) throws Exception {
    ctx.control.reset();
    ctx.control.setEventRate(0);
    drive(ctx, injectJobsAtRate(ctx.control, "list_pools", 100));
  }

  /** 100 jobs/sec + 500 events/sec. Measures priority fairness under mixed load. */
  private static void mixedSteady(Context ctx) throws Exception {
    ctx.control.reset();
    ctx.control.setEventRate(500);
    drive(ctx, injectJobsAtRate(ctx.control, "list_pools", 100));
    ctx.control.setEventRate(0);
  }

  /**
   * 200 events/s sustained. Pair with a higher plugin latency via {@code JOB_LATENCY_MS=200} /
   * {@code EVENT_LATENCY_MS=200} to stress the concurrency cap: at maxConcurrency=5 with a 200
   * ms-per-item plugin, steady-state throughput maxes at ~25 items/s.
   */
  private static void eventsRamp200rps(Context ctx) throws Exception {
    ctx.control.reset();
    ctx.control.setEventRate(200);
    drive(ctx, () -> false);
    ctx.control.setEventRate(0);
  }

  // --- Helpers ---

  /**
   * Runs a load-driver step at ~10 Hz for the duration, recording when to stop. The step returns
   * true to signal it's done early (e.g., finite job injection); returning false means keep running
   * until duration elapses.
   */
  private static void drive(Context ctx, StepFn step) throws Exception {
    long startMs = System.currentTimeMillis();
    long endMs = startMs + ctx.duration.toMillis();
    AtomicBoolean stop = new AtomicBoolean(false);
    while (!stop.get() && System.currentTimeMillis() < endMs) {
      try {
        if (step.step()) stop.set(true);
      } catch (Exception e) {
        log.warn("drive step failed: {}", e.getMessage());
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
  }

  @FunctionalInterface
  private interface StepFn {
    /** Returns true to stop early. */
    boolean step() throws Exception;
  }

  /** Step function that injects {@code rps/10} jobs every 100 ms. */
  private static StepFn injectJobsAtRate(ControlClient control, String type, int rps) {
    int perTick = Math.max(1, rps / 10);
    return () -> {
      try {
        control.injectJobs(type, perTick);
      } catch (Exception e) {
        log.warn("inject failed: {}", e.getMessage());
      }
      return false;
    };
  }
}
