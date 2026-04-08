package com.tcn.exile.handler;

import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import com.tcn.exile.model.Page;
import com.tcn.exile.service.ConfigService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single integration point for CRM plugins. Implementations provide job handling, event
 * handling, and config validation in one place.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Config is polled from the gate
 *   <li>{@link #onConfig} is called — plugin validates and initializes resources
 *   <li>If {@code onConfig} returns {@code true}, the WorkStream opens
 *   <li>Jobs arrive → {@link JobHandler} methods are called
 *   <li>Events arrive → {@link EventHandler} methods are called
 * </ol>
 *
 * <p>Default implementations for {@code listTenantLogs}, {@code setLogLevel}, {@code info}, and
 * {@code diagnostics} are provided using logback-ext's MemoryAppender. Integrations can override
 * these if needed.
 */
public interface Plugin extends JobHandler, EventHandler {

  /**
   * Called when the gate returns a new or changed config. The plugin should validate the config
   * payload and initialize its resources (database connections, HTTP clients, etc.).
   *
   * <p>Return {@code true} if the plugin is ready to handle work. The WorkStream opens only after
   * the first {@code true} return. Return {@code false} to reject the config — the poller will
   * retry on the next cycle.
   */
  default boolean onConfig(ConfigService.ClientConfiguration config) {
    return true;
  }

  /** Human-readable plugin name for diagnostics. */
  default String pluginName() {
    return getClass().getSimpleName();
  }

  /**
   * Returns log entries from the in-memory log buffer. Default implementation reads from
   * logback-ext's MemoryAppender.
   */
  @Override
  default Page<JobHandler.LogEntry> listTenantLogs(
      Instant startTime, Instant endTime, String pageToken, int pageSize) throws Exception {
    MemoryAppender appender = MemoryAppenderInstance.getInstance();
    if (appender == null) {
      return new Page<>(List.of(), "");
    }

    long startMs = startTime != null ? startTime.toEpochMilli() : 0;
    long endMs = endTime != null ? endTime.toEpochMilli() : System.currentTimeMillis();

    var logEvents = appender.getEventsInTimeRange(startMs, endMs);
    var entries =
        logEvents.stream()
            .map(msg -> new JobHandler.LogEntry(Instant.now(), "INFO", "memlogger", msg))
            .limit(pageSize > 0 ? pageSize : 100)
            .toList();

    return new Page<>(entries, "");
  }

  /**
   * Changes the log level at runtime. Default implementation uses logback's API to set the level on
   * the named logger.
   */
  @Override
  default void setLogLevel(String loggerName, String level) throws Exception {
    var loggerContext =
        (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    var logger = loggerContext.getLogger(loggerName);
    if (logger != null) {
      logger.setLevel(ch.qos.logback.classic.Level.valueOf(level));
    }
  }

  /** Returns basic app info. Default implementation returns runtime metadata. */
  @Override
  default Map<String, Object> info() throws Exception {
    return Map.of(
        "appName", pluginName(),
        "runtime", System.getProperty("java.version"),
        "os", System.getProperty("os.name"));
  }

  /** Returns system diagnostics. Default implementation returns JVM stats. */
  @Override
  default JobHandler.DiagnosticsInfo diagnostics() throws Exception {
    var runtime = Runtime.getRuntime();
    return new JobHandler.DiagnosticsInfo(
        Map.of(
            "os", System.getProperty("os.name"),
            "arch", System.getProperty("os.arch"),
            "processors", runtime.availableProcessors()),
        Map.of(
            "java.version", System.getProperty("java.version"),
            "heap.max", runtime.maxMemory(),
            "heap.used", runtime.totalMemory() - runtime.freeMemory()),
        Map.of(),
        Map.of());
  }
}
