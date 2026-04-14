package com.tcn.exile.demo;

import com.tcn.exile.handler.PluginBase;
import com.tcn.exile.model.*;
import com.tcn.exile.model.event.*;
import com.tcn.exile.service.ConfigService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo plugin that validates config, returns stub job data, and logs events. Extends {@link
 * PluginBase} which provides default implementations for logs, diagnostics, info, shutdown, and log
 * level control.
 *
 * <p>Simulates a slow backend by sleeping on each event, with a bounded pipeline controlled by a
 * semaphore. The {@link #availableCapacity()} override tells sati how many more events we can
 * accept, which drives credit-based backpressure against the gate server.
 */
public class DemoPlugin extends PluginBase {

  private static final Logger log = LoggerFactory.getLogger(DemoPlugin.class);
  private volatile boolean configured = false;

  // Simulated processing pipeline — bounded at maxDepth concurrent events.
  private final int maxDepth;
  private final int processingDelayMs;
  private final Semaphore pipeline;
  private final AtomicLong eventsReceived = new AtomicLong();
  private final AtomicLong eventsCompleted = new AtomicLong();
  private final AtomicLong eventsFailed = new AtomicLong();

  public DemoPlugin() {
    this(10, 50); // default: 10 concurrent slots, 50ms simulated processing
  }

  public DemoPlugin(int maxDepth, int processingDelayMs) {
    this.maxDepth = maxDepth;
    this.processingDelayMs = processingDelayMs;
    this.pipeline = new Semaphore(maxDepth);
  }

  /** Returns how many more events we can accept right now. Drives sati's credit flow control. */
  @Override
  public int availableCapacity() {
    if (!configured) return 0;
    return pipeline.availablePermits();
  }

  /** Pipeline stats for the status endpoint. */
  public Map<String, Object> pipelineStats() {
    return Map.of(
        "maxDepth", maxDepth,
        "availablePermits", pipeline.availablePermits(),
        "received", eventsReceived.get(),
        "completed", eventsCompleted.get(),
        "failed", eventsFailed.get(),
        "inPipeline", eventsReceived.get() - eventsCompleted.get() - eventsFailed.get(),
        "processingDelayMs", processingDelayMs);
  }

  /** Simulate slow processing: acquire a pipeline slot, sleep, release. */
  private void simulateProcessing(String eventType, String id) throws Exception {
    eventsReceived.incrementAndGet();
    pipeline.acquire();
    try {
      Thread.sleep(processingDelayMs);
      eventsCompleted.incrementAndGet();
    } catch (Exception e) {
      eventsFailed.incrementAndGet();
      throw e;
    } finally {
      pipeline.release();
    }
  }

  // --- Config ---

  @Override
  public boolean onConfig(ConfigService.ClientConfiguration config) {
    log.info(
        "Plugin received config (org={}, configName={}, payloadKeys={})",
        config.orgId(),
        config.configName(),
        config.configPayload() != null ? config.configPayload().keySet() : "null");
    // In a real plugin, you would parse config.configPayload() for DB credentials,
    // initialize the connection pool, and return false if it fails.
    if (config.configPayload() == null || config.configPayload().isEmpty()) {
      configured = false;
      return false;
    }
    configured = true;
    return true;
  }

  @Override
  public String pluginName() {
    return "demo";
  }

  // --- Jobs ---

  @Override
  public List<Pool> listPools() {
    log.info("listPools called");
    return List.of(
        new Pool("pool-1", "Demo Campaign A", Pool.PoolStatus.READY, 150),
        new Pool("pool-2", "Demo Campaign B", Pool.PoolStatus.NOT_READY, 0));
  }

  @Override
  public Pool getPoolStatus(String poolId) {
    log.info("getPoolStatus called for {}", poolId);
    return new Pool(poolId, "Demo Pool", Pool.PoolStatus.READY, 42);
  }

  @Override
  public Page<DataRecord> getPoolRecords(String poolId, String pageToken, int pageSize) {
    log.info("getPoolRecords called for pool={} page={} size={}", poolId, pageToken, pageSize);
    return new Page<>(
        List.of(
            new DataRecord(poolId, "rec-1", Map.of("name", "John Doe", "phone", "+15551234567")),
            new DataRecord(poolId, "rec-2", Map.of("name", "Jane Smith", "phone", "+15559876543"))),
        "");
  }

  @Override
  public Page<DataRecord> searchRecords(List<Filter> filters, String pageToken, int pageSize) {
    log.info("searchRecords called with {} filters", filters.size());
    return new Page<>(
        List.of(
            new DataRecord("pool-1", "rec-1", Map.of("name", "Search Result", "matched", true))),
        "");
  }

  @Override
  public List<Field> getRecordFields(String poolId, String recordId, List<String> fieldNames) {
    log.info("getRecordFields called for {}/{} fields={}", poolId, recordId, fieldNames);
    return List.of(
        new Field("first_name", "John", poolId, recordId),
        new Field("last_name", "Doe", poolId, recordId),
        new Field("balance", "1250.00", poolId, recordId));
  }

  @Override
  public boolean setRecordFields(String poolId, String recordId, List<Field> fields) {
    log.info("setRecordFields called for {}/{} with {} fields", poolId, recordId, fields.size());
    return true;
  }

  @Override
  public String createPayment(String poolId, String recordId, Map<String, Object> paymentData) {
    log.info("createPayment called for {}/{}: {}", poolId, recordId, paymentData);
    return "PAY-" + System.currentTimeMillis();
  }

  @Override
  public DataRecord popAccount(String poolId, String recordId) {
    log.info("popAccount called for {}/{}", poolId, recordId);
    return new DataRecord(poolId, recordId, Map.of("name", "Popped Account", "status", "active"));
  }

  @Override
  public Map<String, Object> executeLogic(String logicName, Map<String, Object> parameters) {
    log.info("executeLogic called: {} params={}", logicName, parameters);
    return Map.of("result", "ok", "logic", logicName);
  }

  // --- Events (simulated slow processing with bounded pipeline) ---

  @Override
  public void onAgentCall(AgentCallEvent event) throws Exception {
    log.info("AgentCall: callSid={} type={}", event.callSid(), event.callType());
    simulateProcessing("AgentCall", String.valueOf(event.callSid()));
  }

  @Override
  public void onTelephonyResult(TelephonyResultEvent event) throws Exception {
    log.info("TelephonyResult: callSid={} status={}", event.callSid(), event.status());
    simulateProcessing("TelephonyResult", String.valueOf(event.callSid()));
  }

  @Override
  public void onAgentResponse(AgentResponseEvent event) throws Exception {
    log.info("AgentResponse: callSid={} key={}", event.callSid(), event.responseKey());
    simulateProcessing("AgentResponse", String.valueOf(event.callSid()));
  }

  @Override
  public void onTransferInstance(TransferInstanceEvent event) throws Exception {
    log.info("TransferInstance: id={}", event.transferInstanceId());
    simulateProcessing("TransferInstance", String.valueOf(event.transferInstanceId()));
  }

  @Override
  public void onCallRecording(CallRecordingEvent event) throws Exception {
    log.info("CallRecording: id={} callSid={}", event.recordingId(), event.callSid());
    simulateProcessing("CallRecording", String.valueOf(event.callSid()));
  }

  @Override
  public void onTask(TaskEvent event) throws Exception {
    log.info("Task: sid={} status={}", event.taskSid(), event.status());
    simulateProcessing("Task", String.valueOf(event.taskSid()));
  }
}
