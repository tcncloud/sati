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
    var mem = java.lang.management.ManagementFactory.getMemoryMXBean();
    var heap = mem.getHeapMemoryUsage();
    var nonHeap = mem.getNonHeapMemoryUsage();
    var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    var thread = java.lang.management.ManagementFactory.getThreadMXBean();

    // System info.
    var systemInfo = new java.util.LinkedHashMap<String, Object>();
    systemInfo.put("os.name", System.getProperty("os.name"));
    systemInfo.put("os.version", System.getProperty("os.version"));
    systemInfo.put("os.arch", System.getProperty("os.arch"));
    systemInfo.put("processors", rt.availableProcessors());
    systemInfo.put("system.load.average", os.getSystemLoadAverage());
    systemInfo.put("hostname", getHostname());

    // Detect container environment.
    var containerFile = new java.io.File("/.dockerenv");
    if (containerFile.exists()) {
      systemInfo.put("container", "docker");
    }
    var cgroupFile = new java.io.File("/proc/1/cgroup");
    if (cgroupFile.exists()) {
      systemInfo.put("cgroup", true);
    }
    var podName = System.getenv("HOSTNAME");
    if (podName != null) {
      systemInfo.put("pod.name", podName);
    }

    // Storage.
    for (var root : java.io.File.listRoots()) {
      systemInfo.put(
          "storage." + root.getAbsolutePath().replace("/", "root"),
          Map.of(
              "total", root.getTotalSpace(),
              "free", root.getFreeSpace(),
              "usable", root.getUsableSpace()));
    }

    // Runtime info.
    var runtimeInfo = new java.util.LinkedHashMap<String, Object>();
    runtimeInfo.put("java.version", System.getProperty("java.version"));
    runtimeInfo.put("java.vendor", System.getProperty("java.vendor"));
    runtimeInfo.put("java.vm.name", System.getProperty("java.vm.name"));
    runtimeInfo.put("java.vm.version", System.getProperty("java.vm.version"));
    runtimeInfo.put("heap.init", heap.getInit());
    runtimeInfo.put("heap.used", heap.getUsed());
    runtimeInfo.put("heap.committed", heap.getCommitted());
    runtimeInfo.put("heap.max", heap.getMax());
    runtimeInfo.put("non_heap.used", nonHeap.getUsed());
    runtimeInfo.put("non_heap.committed", nonHeap.getCommitted());
    runtimeInfo.put("threads.live", thread.getThreadCount());
    runtimeInfo.put("threads.daemon", thread.getDaemonThreadCount());
    runtimeInfo.put("threads.peak", thread.getPeakThreadCount());
    runtimeInfo.put("threads.total_started", thread.getTotalStartedThreadCount());
    runtimeInfo.put(
        "uptime.ms", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());

    // GC info.
    for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
      runtimeInfo.put("gc." + gc.getName() + ".count", gc.getCollectionCount());
      runtimeInfo.put("gc." + gc.getName() + ".time_ms", gc.getCollectionTime());
    }

    // Database info — empty by default, plugins override to add connection pool stats.
    var databaseInfo = new java.util.LinkedHashMap<String, Object>();

    // Custom — plugin name and memory appender stats.
    var custom = new java.util.LinkedHashMap<String, Object>();
    custom.put("plugin.name", pluginName());
    var appender = MemoryAppenderInstance.getInstance();
    if (appender != null) {
      custom.put("memlogger.events", appender.getEventsWithTimestamps().size());
    }

    return new JobHandler.DiagnosticsInfo(systemInfo, runtimeInfo, databaseInfo, custom);
  }

  private static String getHostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      var hostname = System.getenv("HOSTNAME");
      return hostname != null ? hostname : "unknown";
    }
  }

  // --- Shutdown ---

  @Override
  public void shutdown(String reason) throws Exception {
    log.warn("Shutdown requested: {}", reason);
  }
}
