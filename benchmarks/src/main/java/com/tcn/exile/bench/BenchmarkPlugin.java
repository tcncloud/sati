package com.tcn.exile.bench;

import com.tcn.exile.handler.PluginBase;
import com.tcn.exile.model.*;
import com.tcn.exile.model.event.*;
import com.tcn.exile.service.ConfigService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Plugin whose handler methods sleep for a configurable, jittered duration to simulate work. Each
 * handler records its processing latency into the appropriate LatencyHistogram.
 *
 * <p>All methods are thread-safe: handlers are invoked from virtual threads by {@link
 * com.tcn.exile.ExileClient}.
 */
public final class BenchmarkPlugin extends PluginBase {

  private final long jobLatencyNanos;
  private final long jobJitterNanos;
  private final long eventLatencyNanos;
  private final long eventJitterNanos;
  private final double errorRate;

  final LatencyHistogram jobLatency;
  final LatencyHistogram eventLatency;

  BenchmarkPlugin(
      long jobLatencyNanos,
      long jobJitterNanos,
      long eventLatencyNanos,
      long eventJitterNanos,
      double errorRate) {
    this.jobLatencyNanos = jobLatencyNanos;
    this.jobJitterNanos = jobJitterNanos;
    this.eventLatencyNanos = eventLatencyNanos;
    this.eventJitterNanos = eventJitterNanos;
    this.errorRate = errorRate;
    this.jobLatency = new LatencyHistogram(65_536);
    this.eventLatency = new LatencyHistogram(65_536);
  }

  @Override
  public boolean onConfig(ConfigService.ClientConfiguration config) {
    return true;
  }

  // --- Jobs ---
  @Override
  public List<Pool> listPools() throws Exception {
    doJob();
    return List.of();
  }

  @Override
  public Pool getPoolStatus(String poolId) throws Exception {
    doJob();
    return new Pool(poolId, "bench-pool", Pool.PoolStatus.READY, 0);
  }

  @Override
  public Page<DataRecord> getPoolRecords(String poolId, String pageToken, int pageSize)
      throws Exception {
    doJob();
    return new Page<>(List.of(), "");
  }

  @Override
  public Page<DataRecord> searchRecords(List<Filter> filters, String pageToken, int pageSize)
      throws Exception {
    doJob();
    return new Page<>(List.of(), "");
  }

  @Override
  public List<Field> getRecordFields(String poolId, String recordId, List<String> fieldNames)
      throws Exception {
    doJob();
    return List.of();
  }

  @Override
  public boolean setRecordFields(String poolId, String recordId, List<Field> fields)
      throws Exception {
    doJob();
    return true;
  }

  @Override
  public String createPayment(String poolId, String recordId, Map<String, Object> paymentData)
      throws Exception {
    doJob();
    return "bench-payment-id";
  }

  @Override
  public DataRecord popAccount(String poolId, String recordId) throws Exception {
    doJob();
    return new DataRecord(poolId, recordId, Map.of());
  }

  @Override
  public Map<String, Object> executeLogic(String logicName, Map<String, Object> parameters)
      throws Exception {
    doJob();
    return Map.of();
  }

  // --- Events ---
  @Override
  public void onAgentCall(AgentCallEvent event) throws Exception {
    doEvent();
  }

  @Override
  public void onTelephonyResult(TelephonyResultEvent event) throws Exception {
    doEvent();
  }

  @Override
  public void onAgentResponse(AgentResponseEvent event) throws Exception {
    doEvent();
  }

  @Override
  public void onTransferInstance(TransferInstanceEvent event) throws Exception {
    doEvent();
  }

  @Override
  public void onCallRecording(CallRecordingEvent event) throws Exception {
    doEvent();
  }

  @Override
  public void onTask(TaskEvent event) throws Exception {
    doEvent();
  }

  // --- Internals ---
  private void doJob() throws Exception {
    long start = System.nanoTime();
    try {
      sleep(jobLatencyNanos, jobJitterNanos);
      maybeError();
    } finally {
      jobLatency.record(System.nanoTime() - start);
    }
  }

  private void doEvent() throws Exception {
    long start = System.nanoTime();
    try {
      sleep(eventLatencyNanos, eventJitterNanos);
      maybeError();
    } finally {
      eventLatency.record(System.nanoTime() - start);
    }
  }

  private void sleep(long base, long jitter) throws InterruptedException {
    long d = base;
    if (jitter > 0) {
      d += ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }
    if (d <= 0) return;
    Thread.sleep(d / 1_000_000, (int) (d % 1_000_000));
  }

  private void maybeError() throws Exception {
    if (errorRate > 0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
      throw new RuntimeException("synthetic benchmark error");
    }
  }
}
