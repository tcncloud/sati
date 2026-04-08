package com.tcn.exile.demo;

import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.model.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub job handler that returns fake data for demonstration and testing. Each method logs the
 * request and returns plausible dummy responses.
 */
public class DemoJobHandler implements JobHandler {

  private static final Logger log = LoggerFactory.getLogger(DemoJobHandler.class);

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
    var records =
        List.of(
            new DataRecord(poolId, "rec-1", Map.of("name", "John Doe", "phone", "+15551234567")),
            new DataRecord(poolId, "rec-2", Map.of("name", "Jane Smith", "phone", "+15559876543")));
    return new Page<>(records, "");
  }

  @Override
  public Page<DataRecord> searchRecords(List<Filter> filters, String pageToken, int pageSize) {
    log.info("searchRecords called with {} filters", filters.size());
    var records =
        List.of(
            new DataRecord("pool-1", "rec-1", Map.of("name", "Search Result", "matched", true)));
    return new Page<>(records, "");
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

  @Override
  public Map<String, Object> info() {
    return Map.of(
        "appName",
        "sati-demo",
        "appVersion",
        Main.VERSION,
        "runtime",
        System.getProperty("java.version"),
        "os",
        System.getProperty("os.name"));
  }

  @Override
  public void shutdown(String reason) {
    log.warn("Shutdown requested: {}", reason);
    // In a real integration, this would trigger graceful shutdown.
  }

  @Override
  public void processLog(String payload) {
    log.info("Remote log: {}", payload);
  }

  @Override
  public DiagnosticsInfo diagnostics() {
    var runtime = Runtime.getRuntime();
    return new DiagnosticsInfo(
        Map.of(
            "os", System.getProperty("os.name"),
            "arch", System.getProperty("os.arch"),
            "processors", runtime.availableProcessors()),
        Map.of(
            "java.version", System.getProperty("java.version"),
            "heap.max", runtime.maxMemory(),
            "heap.used", runtime.totalMemory() - runtime.freeMemory()),
        Map.of("type", "demo", "connected", false),
        Map.of("demo", true));
  }

  @Override
  public Page<LogEntry> listTenantLogs(
      Instant startTime, Instant endTime, String pageToken, int pageSize) {
    log.info("listTenantLogs called from {} to {}", startTime, endTime);
    return new Page<>(
        List.of(new LogEntry(Instant.now(), "INFO", "demo", "This is a demo log entry")), "");
  }

  @Override
  public void setLogLevel(String loggerName, String level) {
    log.info("setLogLevel called: {}={}", loggerName, level);
  }
}
