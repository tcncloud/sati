package com.tcn.exile.handler;

import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import com.tcn.exile.model.Page;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private static final Set<String> VALID_LEVELS =
      Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "ALL");

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
    var matching =
        logEvents.stream()
            .filter(e -> e.timestamp >= startMs && e.timestamp <= endMs)
            .map(
                e ->
                    new JobHandler.LogEntry(
                        Instant.ofEpochMilli(e.timestamp),
                        e.level != null ? e.level : "INFO",
                        e.loggerName != null ? e.loggerName : "memlogger",
                        e.formattedMessage != null ? e.formattedMessage : e.message));
    var entries = pageSize > 0 ? matching.limit(pageSize).toList() : matching.toList();
    log.info("listTenantLogs: returning {} entries", entries.size());

    return new Page<>(entries, "");
  }

  @Override
  public void setLogLevel(String loggerName, String level) throws Exception {
    var requested = level != null ? level.toUpperCase() : null;
    if (requested == null || !VALID_LEVELS.contains(requested)) {
      throw new IllegalArgumentException(
          "Unknown log level '" + level + "', expected one of " + VALID_LEVELS);
    }
    var loggerContext =
        (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    var logger = loggerContext.getLogger(loggerName);
    var newLevel = ch.qos.logback.classic.Level.valueOf(requested);
    logger.setLevel(newLevel);
    log.info("Log level changed: {}={}", loggerName, newLevel);
  }

  @Override
  public Map<String, String> loggerLevels() throws Exception {
    var loggerContext =
        (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

    var levels = new java.util.LinkedHashMap<String, String>();
    for (var logger : loggerContext.getLoggerList()) {
      var level = logger.getLevel() != null ? logger.getLevel() : logger.getEffectiveLevel();
      if (level != null) {
        levels.put(logger.getName(), level.toString());
      }
    }
    return levels;
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
    putIfPresent(systemInfo, "hostname", getHostname());

    putIfPresent(systemInfo, "system.uptime.s", systemUptimeSeconds());
    if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
      systemInfo.put("os.memory.total", sunOs.getTotalMemorySize());
      systemInfo.put("os.memory.free", sunOs.getFreeMemorySize());
      systemInfo.put("os.swap.total", sunOs.getTotalSwapSpaceSize());
      systemInfo.put("os.swap.free", sunOs.getFreeSwapSpaceSize());
    }

    putIfPresent(systemInfo, "hardware.model", readFile(DMI_DIR + "product_name"));
    putIfPresent(systemInfo, "hardware.manufacturer", readFile(DMI_DIR + "sys_vendor"));
    putIfPresent(systemInfo, "hardware.serial", readFile(DMI_DIR + "product_serial"));
    putIfPresent(systemInfo, "hardware.uuid", readFile(DMI_DIR + "product_uuid"));
    putIfPresent(systemInfo, "processor.name", processorName());

    // Detect container environment.
    if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
      systemInfo.put("container", "kubernetes");
    } else if (new java.io.File("/.dockerenv").exists()) {
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
    putIfPresent(systemInfo, "container.id", containerId(readFile("/proc/self/cgroup")));
    putIfPresent(systemInfo, "container.image", containerImage());
    var limits = containerLimits(CGROUP_DIR);
    if (!limits.isEmpty()) {
      systemInfo.put("container.limits", limits);
    }

    var env = new java.util.LinkedHashMap<String, Object>();
    for (var name : ENV_NAMES) {
      putIfPresent(env, name, System.getenv(name));
    }
    systemInfo.put("env", env);

    var props = new java.util.LinkedHashMap<String, Object>();
    for (var name : SYSTEM_PROPERTY_NAMES) {
      putIfPresent(props, name, System.getProperty(name));
    }
    systemInfo.put("system_properties", props);

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
    for (var name :
        List.of(
            "java.runtime.name",
            "java.vm.vendor",
            "java.specification.name",
            "java.specification.version",
            "java.class.path",
            "java.library.path")) {
      putIfPresent(runtimeInfo, name, System.getProperty(name));
    }
    runtimeInfo.put(
        "start.ms", java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
    runtimeInfo.put("heap.init", heap.getInit());
    runtimeInfo.put("heap.used", heap.getUsed());
    runtimeInfo.put("heap.committed", heap.getCommitted());
    runtimeInfo.put("heap.max", heap.getMax());
    runtimeInfo.put("non_heap.used", nonHeap.getUsed());
    runtimeInfo.put("non_heap.committed", nonHeap.getCommitted());
    runtimeInfo.put("non_heap.max", nonHeap.getMax());
    var pools = new java.util.ArrayList<Object>();
    for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
      var usage = pool.getUsage();
      if (usage == null) continue;
      pools.add(
          Map.of(
              "name", pool.getName(),
              "type", pool.getType().toString(),
              "used", usage.getUsed(),
              "max", usage.getMax(),
              "committed", usage.getCommitted()));
    }
    runtimeInfo.put("memory_pools", pools);
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

  private static final String DMI_DIR = "/sys/devices/virtual/dmi/id/";
  private static final String CGROUP_DIR = "/sys/fs/cgroup";

  static final List<String> ENV_NAMES =
      List.of(
          "LANGUAGE", "PATH", "HOSTNAME", "LC_ALL", "JAVA_HOME", "JAVA_VERSION", "LANG", "HOME");

  // The names behind the admin page's System Properties card. A fixed list on purpose: a -D flag
  // can carry a password. sun.java.command is left out because it holds the program arguments.
  static final List<String> SYSTEM_PROPERTY_NAMES =
      List.of(
          "java.specification.version",
          "java.specification.vendor",
          "java.specification.name",
          "java.specification.maintenance.version",
          "java.version",
          "java.version.date",
          "java.vendor",
          "java.vendor.version",
          "java.vendor.url",
          "java.vendor.url.bug",
          "java.runtime.name",
          "java.runtime.version",
          "java.home",
          "java.class.path",
          "java.library.path",
          "java.class.version",
          "java.vm.name",
          "java.vm.version",
          "java.vm.vendor",
          "java.vm.info",
          "java.vm.specification.version",
          "java.vm.specification.vendor",
          "java.vm.specification.name",
          "java.vm.compressedOopsMode",
          "os.name",
          "os.version",
          "os.arch",
          "user.name",
          "user.home",
          "user.dir",
          "user.timezone",
          "user.country",
          "user.language",
          "file.separator",
          "path.separator",
          "line.separator",
          "file.encoding",
          "native.encoding",
          "sun.jnu.encoding",
          "sun.arch.data.model",
          "sun.java.launcher",
          "sun.boot.library.path",
          "sun.cpu.endian",
          "sun.management.compiler",
          "sun.io.unicode.encoding",
          "jdk.debug",
          "java.io.tmpdir",
          "env",
          "micronaut.classloader.logging",
          "io.netty.allocator.maxOrder",
          "io.netty.processId",
          "io.netty.machineId",
          "com.zaxxer.hikari.pool_number");

  private static final java.util.regex.Pattern CONTAINER_ID =
      java.util.regex.Pattern.compile("[0-9a-f]{64}");

  private static void putIfPresent(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  // Returns null when the file is missing, unreadable or empty.
  private static String readFile(String path) {
    try {
      var s = java.nio.file.Files.readString(java.nio.file.Path.of(path)).trim();
      return s.isEmpty() ? null : s;
    } catch (Exception e) {
      return null;
    }
  }

  private static Long systemUptimeSeconds() {
    var uptime = readFile("/proc/uptime");
    if (uptime == null) return null;
    try {
      return (long) Double.parseDouble(uptime.split("\\s+")[0]);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String processorName() {
    var id = System.getenv("PROCESSOR_IDENTIFIER");
    if (id != null && !id.isBlank()) return id;
    var cpuinfo = readFile("/proc/cpuinfo");
    if (cpuinfo == null) return null;
    for (var line : cpuinfo.split("\n")) {
      if (line.startsWith("model name")) {
        return line.substring(line.indexOf(':') + 1).trim();
      }
    }
    return null;
  }

  // The first 12 characters of the 64-hex container ID in /proc/self/cgroup, as docker shows it.
  static String containerId(String cgroup) {
    if (cgroup == null) return null;
    var m = CONTAINER_ID.matcher(cgroup);
    return m.find() ? m.group().substring(0, 12) : null;
  }

  private static String containerImage() {
    for (var name :
        List.of("IMAGE_NAME", "DOCKER_IMAGE", "CONTAINER_IMAGE", "POD_CONTAINER_IMAGE")) {
      var v = System.getenv(name);
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  /**
   * memory_limit in bytes and cpu_limit in CPUs, from cgroup v2 and else cgroup v1. A limit that
   * isn't set is left out.
   */
  static Map<String, Object> containerLimits(String cgroupDir) {
    var limits = new java.util.LinkedHashMap<String, Object>();

    var memory = readFile(cgroupDir + "/memory.max");
    if (memory == null) memory = readFile(cgroupDir + "/memory/memory.limit_in_bytes");
    try {
      // cgroup v1 reports "no limit" as a huge number instead of "max".
      if (memory != null && !memory.equals("max") && Long.parseLong(memory) < (1L << 50)) {
        limits.put("memory_limit", memory);
      }
    } catch (NumberFormatException ignored) {
    }

    String quota = null;
    String period = null;
    var cpuMax = readFile(cgroupDir + "/cpu.max");
    if (cpuMax != null) {
      var parts = cpuMax.split("\\s+");
      quota = parts[0];
      period = parts.length > 1 ? parts[1] : null;
    } else {
      quota = readFile(cgroupDir + "/cpu/cpu.cfs_quota_us");
      period = readFile(cgroupDir + "/cpu/cpu.cfs_period_us");
    }
    try {
      if (quota != null && period != null && !quota.equals("max") && !quota.equals("-1")) {
        double cpus = Double.parseDouble(quota) / Double.parseDouble(period);
        limits.put("cpu_limit", String.valueOf(Math.round(cpus * 100) / 100.0));
      }
    } catch (NumberFormatException ignored) {
    }
    return limits;
  }

  private static String getHostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return System.getenv("HOSTNAME");
    }
  }

  // --- Shutdown ---

  @Override
  public void shutdown(String reason) throws Exception {
    log.warn("Shutdown requested: {}. Exiting in 2 seconds.", reason);
    Thread.ofVirtual()
        .name("exile-shutdown")
        .start(
            () -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              log.info("Exiting now (reason: {})", reason);
              System.exit(0);
            });
  }
}
