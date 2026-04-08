package com.tcn.exile.handler;

import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import com.tcn.exile.model.Page;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for plugins that provides default implementations for common operations: log
 * retrieval, log level control, diagnostics, info, shutdown, and remote logging.
 *
 * <p>Extend this class and override only the CRM-specific methods (pool/record operations, event
 * handlers, config validation).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * public class FinviPlugin extends PluginBase {
 *     private HikariDataSource dataSource;
 *
 *     @Override
 *     public boolean onConfig(ClientConfiguration config) {
 *         dataSource = initDataSource(config.configPayload());
 *         return dataSource != null;
 *     }
 *
 *     @Override
 *     public List<Pool> listPools() {
 *         return db.query("SELECT ...");
 *     }
 * }
 * }</pre>
 */
public abstract class PluginBase implements Plugin {

  private static final Logger log = LoggerFactory.getLogger(PluginBase.class);

  @Override
  public String pluginName() {
    return getClass().getSimpleName();
  }

  // --- Logs ---

  @Override
  public Page<JobHandler.LogEntry> listTenantLogs(
      Instant startTime, Instant endTime, String pageToken, int pageSize) throws Exception {
    MemoryAppender appender = MemoryAppenderInstance.getInstance();
    if (appender == null) {
      return new Page<>(List.of(), "");
    }

    long startMs = startTime != null ? startTime.toEpochMilli() : 0;
    long endMs = endTime != null ? endTime.toEpochMilli() : System.currentTimeMillis();

    var logEvents = appender.getEventsWithTimestamps();
    log.info(
        "listTenantLogs: appender has {} events, range {}..{}, filtering",
        logEvents.size(),
        startMs,
        endMs);
    var entries =
        logEvents.stream()
            .filter(e -> e.timestamp >= startMs && e.timestamp <= endMs)
            .map(
                e ->
                    new JobHandler.LogEntry(
                        Instant.ofEpochMilli(e.timestamp), "INFO", "memlogger", e.message))
            .limit(pageSize > 0 ? pageSize : 100)
            .toList();
    log.info("listTenantLogs: returning {} entries", entries.size());

    return new Page<>(entries, "");
  }

  @Override
  public void setLogLevel(String loggerName, String level) throws Exception {
    var loggerContext =
        (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    var logger = loggerContext.getLogger(loggerName);
    if (logger != null) {
      var newLevel = ch.qos.logback.classic.Level.valueOf(level);
      logger.setLevel(newLevel);
      log.info("Log level changed: {}={}", loggerName, newLevel);
    }
  }

  @Override
  public void processLog(String payload) throws Exception {
    log.info("Remote log: {}", payload);
  }

  // --- Info & Diagnostics ---

  @Override
  public Map<String, Object> info() throws Exception {
    return Map.of(
        "appName", pluginName(),
        "runtime", System.getProperty("java.version"),
        "os", System.getProperty("os.name"));
  }

  @Override
  public JobHandler.DiagnosticsInfo diagnostics() throws Exception {
    var rt = Runtime.getRuntime();
    return new JobHandler.DiagnosticsInfo(
        Map.of(
            "os", System.getProperty("os.name"),
            "arch", System.getProperty("os.arch"),
            "processors", rt.availableProcessors()),
        Map.of(
            "java.version", System.getProperty("java.version"),
            "heap.max", rt.maxMemory(),
            "heap.used", rt.totalMemory() - rt.freeMemory()),
        Map.of(),
        Map.of());
  }

  // --- Shutdown ---

  @Override
  public void shutdown(String reason) throws Exception {
    log.warn("Shutdown requested: {}", reason);
  }
}
