/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.config;

import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.DiagnosticsResult;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.DiagnosticsResult.*;
import com.google.protobuf.Timestamp;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.lang.management.*;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DiagnosticsService {
  private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

  private static final Pattern DOCKER_PATTERN =
      Pattern.compile(".*docker.*", Pattern.CASE_INSENSITIVE);
  private static final Pattern KUBERNETES_PATTERN =
      Pattern.compile(".*k8s.*|.*kube.*", Pattern.CASE_INSENSITIVE);

  // Helper class to hold log collection results
  private static class LogCollectionResult {
    final List<String> logs;
    final boolean success;
    final String errorMessage;

    LogCollectionResult(List<String> logs, boolean success, String errorMessage) {
      this.logs = logs != null ? logs : new ArrayList<>();
      this.success = success;
      this.errorMessage = errorMessage;
    }

    static LogCollectionResult success(List<String> logs) {
      return new LogCollectionResult(logs, true, null);
    }

    static LogCollectionResult failure(String errorMessage) {
      return new LogCollectionResult(null, false, errorMessage);
    }
  }

  public DiagnosticsResult collectSystemDiagnostics() {
    log.info("Collecting comprehensive system diagnostics...");

    try {
      Instant now = Instant.now();
      Timestamp timestamp =
          Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();

      // Collect Hikari metrics and config details
      List<HikariPoolMetrics> hikariPoolMetrics = collectHikariMetrics();
      ConfigDetails configDetails = collectConfigDetails();

      return DiagnosticsResult.newBuilder()
          .setTimestamp(timestamp)
          .setHostname(getHostname())
          .setOperatingSystem(collectOperatingSystemInfo())
          .setJavaRuntime(collectJavaRuntimeInfo())
          .setHardware(collectHardwareInfo())
          .setMemory(collectMemoryInfo())
          .addAllStorage(collectStorageInfo())
          .setContainer(collectContainerInfo())
          .setEnvironmentVariables(collectEnvironmentVariables())
          .setSystemProperties(collectSystemProperties())
          .addAllHikariPoolMetrics(hikariPoolMetrics)
          .setConfigDetails(configDetails)
          .build();
    } catch (Exception e) {
      log.error("Error collecting system diagnostics", e);
      throw new RuntimeException("Failed to collect system diagnostics", e);
    }
  }

  /**
   * Collects tenant logs and returns them in protobuf format for plugin responses. This method
   * handles time range filtering and log level detection.
   *
   * @param listTenantLogsRequest The request containing optional time range filtering
   * @return ListTenantLogsResult in protobuf format ready for submission to gate
   */
  public build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
      collectTenantLogs(
          build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse.ListTenantLogsRequest
              listTenantLogsRequest) {
    log.debug("Collecting tenant logs from memory appender...");

    long startTime = System.currentTimeMillis();

    try {
      // Extract time range from request if provided
      Long startTimeMs = null;
      Long endTimeMs = null;
      if (listTenantLogsRequest.hasTimeRange()) {
        // Use the exact same timestamp conversion logic that was working before
        startTimeMs =
            listTenantLogsRequest.getTimeRange().getStartTime().getSeconds() * 1000
                + listTenantLogsRequest.getTimeRange().getStartTime().getNanos() / 1000000;
        endTimeMs =
            listTenantLogsRequest.getTimeRange().getEndTime().getSeconds() * 1000
                + listTenantLogsRequest.getTimeRange().getEndTime().getNanos() / 1000000;
        log.debug(
            "Filtering logs with time range: {} to {} (timestamps: {} to {})",
            listTenantLogsRequest.getTimeRange().getStartTime(),
            listTenantLogsRequest.getTimeRange().getEndTime(),
            startTimeMs,
            endTimeMs);
      }

      // Collect logs using common method
      LogCollectionResult result = collectLogsFromMemoryAppender(startTimeMs, endTimeMs);

      // Create protobuf result
      build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult.Builder
          resultBuilder =
              build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
                  .newBuilder();

      if (result.success && !result.logs.isEmpty()) {
        // Create a single log group with all logs
        build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult.LogGroup
                .Builder
            logGroupBuilder =
                build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
                    .LogGroup.newBuilder()
                    .setName("logGroups/memory-logs")
                    .addAllLogs(result.logs);

        // Set time range
        if (listTenantLogsRequest.hasTimeRange()) {
          logGroupBuilder.setTimeRange(listTenantLogsRequest.getTimeRange());
        } else {
          // Fallback to current time
          long now = System.currentTimeMillis();
          logGroupBuilder.setTimeRange(
              build.buf.gen.tcnapi.exile.gate.v2.TimeRange.newBuilder()
                  .setStartTime(createTimestamp(now))
                  .setEndTime(createTimestamp(now))
                  .build());
        }

        // Detect log levels from the actual logs
        build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult.LogGroup
                .LogLevel
            detectedLevel = detectLogLevelFromLogs(result.logs);
        logGroupBuilder.putLogLevels("memory", detectedLevel);

        resultBuilder.addLogGroups(logGroupBuilder.build());
      }

      long duration = System.currentTimeMillis() - startTime;
      log.debug("Successfully collected tenant logs in {}ms", duration);

      return resultBuilder.build();
    } catch (Exception e) {
      log.error("Error collecting tenant logs: {}", e.getMessage(), e);
      return build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
          .newBuilder()
          .build();
    }
  }

  /**
   * Collects tenant logs from the MemoryAppender and returns them as a serializable result. Uses
   * reflection to access MemoryAppender classes since they're in a different module.
   *
   * @return TenantLogsResult containing the collected logs
   */
  public com.tcn.exile.models.TenantLogsResult collectSerdeableTenantLogs() {
    return collectSerdeableTenantLogs(null, null);
  }

  /**
   * Collects tenant logs from the MemoryAppender within a specific time range.
   *
   * @param startTimeMs Start time in milliseconds since epoch, or null for no start limit
   * @param endTimeMs End time in milliseconds since epoch, or null for no end limit
   * @return TenantLogsResult containing the collected logs
   */
  public com.tcn.exile.models.TenantLogsResult collectSerdeableTenantLogs(
      Long startTimeMs, Long endTimeMs) {
    log.debug("Collecting tenant logs from memory appender...");

    try {
      // Collect logs using common method
      LogCollectionResult result = collectLogsFromMemoryAppender(startTimeMs, endTimeMs);

      // Create the result object
      com.tcn.exile.models.TenantLogsResult tenantLogsResult =
          new com.tcn.exile.models.TenantLogsResult();
      List<com.tcn.exile.models.TenantLogsResult.LogGroup> logGroups = new ArrayList<>();

      if (result.success && !result.logs.isEmpty()) {
        // Create a single log group with all logs
        com.tcn.exile.models.TenantLogsResult.LogGroup logGroup =
            new com.tcn.exile.models.TenantLogsResult.LogGroup();
        logGroup.setName("logGroups/memory-logs");
        logGroup.setLogs(result.logs);

        // Set time range
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant oneHourAgo = now.minusSeconds(3600); // 1 hour ago

        com.tcn.exile.models.TenantLogsResult.LogGroup.TimeRange timeRange =
            new com.tcn.exile.models.TenantLogsResult.LogGroup.TimeRange();
        timeRange.setStartTime(oneHourAgo);
        timeRange.setEndTime(now);
        logGroup.setTimeRange(timeRange);

        // Set log levels (default to INFO for all components)
        Map<String, com.tcn.exile.models.TenantLogsResult.LogGroup.LogLevel> logLevels =
            new HashMap<>();
        logLevels.put("memory", com.tcn.exile.models.TenantLogsResult.LogGroup.LogLevel.INFO);
        logGroup.setLogLevels(logLevels);

        logGroups.add(logGroup);
        log.info("Created log group with {} log entries", result.logs.size());
      } else {
        log.info("No logs found in memory appender");
      }

      tenantLogsResult.setLogGroups(logGroups);
      tenantLogsResult.setNextPageToken(""); // No pagination for now

      return tenantLogsResult;
    } catch (Exception e) {
      log.error("Error collecting tenant logs", e);
      // Return empty result on error
      com.tcn.exile.models.TenantLogsResult result = new com.tcn.exile.models.TenantLogsResult();
      result.setLogGroups(new ArrayList<>());
      result.setNextPageToken("");
      return result;
    }
  }

  /**
   * Detects the most verbose log level from a collection of log messages. Returns the highest level
   * found (DEBUG > INFO > WARN > ERROR).
   */
  private build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult.LogGroup
          .LogLevel
      detectLogLevelFromLogs(List<String> logs) {
    boolean hasDebug = false;
    boolean hasInfo = false;
    boolean hasWarn = false;
    boolean hasError = false;

    for (String logLine : logs) {
      String upperLogLine = logLine.toUpperCase();
      if (upperLogLine.contains(" DEBUG ")) {
        hasDebug = true;
      } else if (upperLogLine.contains(" INFO ")) {
        hasInfo = true;
      } else if (upperLogLine.contains(" WARN ")) {
        hasWarn = true;
      } else if (upperLogLine.contains(" ERROR ")) {
        hasError = true;
      }
    }

    // Return the most verbose level found
    if (hasDebug) {
      return build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
          .LogGroup.LogLevel.DEBUG;
    } else if (hasInfo) {
      return build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
          .LogGroup.LogLevel.INFO;
    } else if (hasWarn) {
      return build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
          .LogGroup.LogLevel.WARNING;
    } else if (hasError) {
      return build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
          .LogGroup.LogLevel.ERROR;
    } else {
      // Default to INFO if no recognizable log level found
      return build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult
          .LogGroup.LogLevel.INFO;
    }
  }

  private String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "unknown";
    }
  }

  private OperatingSystem collectOperatingSystemInfo() {
    OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

    long totalPhysicalMemory = -1;
    long availablePhysicalMemory = -1;
    long totalSwapSpace = -1;
    long availableSwapSpace = -1;

    // Try to get extended info if available
    try {
      if (osBean.getClass().getName().contains("com.sun.management")) {
        // Use reflection to access com.sun.management.OperatingSystemMXBean methods
        totalPhysicalMemory =
            (Long) osBean.getClass().getMethod("getTotalPhysicalMemorySize").invoke(osBean);
        availablePhysicalMemory =
            (Long) osBean.getClass().getMethod("getFreePhysicalMemorySize").invoke(osBean);
        totalSwapSpace = (Long) osBean.getClass().getMethod("getTotalSwapSpaceSize").invoke(osBean);
        availableSwapSpace =
            (Long) osBean.getClass().getMethod("getFreeSwapSpaceSize").invoke(osBean);
      }
    } catch (Exception e) {
      log.debug("Could not access extended OS MXBean methods", e);
    }

    return OperatingSystem.newBuilder()
        .setName(osBean.getName())
        .setVersion(osBean.getVersion())
        .setArchitecture(osBean.getArch())
        .setManufacturer(System.getProperty("os.name"))
        .setAvailableProcessors(osBean.getAvailableProcessors())
        .setSystemUptime(getSystemUptime())
        .setSystemLoadAverage(osBean.getSystemLoadAverage())
        .setTotalPhysicalMemory(totalPhysicalMemory)
        .setAvailablePhysicalMemory(availablePhysicalMemory)
        .setTotalSwapSpace(totalSwapSpace)
        .setAvailableSwapSpace(availableSwapSpace)
        .build();
  }

  private long getSystemUptime() {
    try {
      RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
      return runtimeBean.getUptime();
    } catch (Exception e) {
      log.debug("Could not get system uptime", e);
      return -1;
    }
  }

  private JavaRuntime collectJavaRuntimeInfo() {
    RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    JavaRuntime.Builder builder =
        JavaRuntime.newBuilder()
            .setVersion(System.getProperty("java.version", ""))
            .setVendor(System.getProperty("java.vendor", ""))
            .setRuntimeName(System.getProperty("java.runtime.name", ""))
            .setVmName(System.getProperty("java.vm.name", ""))
            .setVmVersion(System.getProperty("java.vm.version", ""))
            .setVmVendor(System.getProperty("java.vm.vendor", ""))
            .setSpecificationName(System.getProperty("java.specification.name", ""))
            .setSpecificationVersion(System.getProperty("java.specification.version", ""))
            .setClassPath(System.getProperty("java.class.path", ""))
            .setLibraryPath(System.getProperty("java.library.path", ""))
            .setUptime(runtimeBean.getUptime())
            .setStartTime(runtimeBean.getStartTime())
            .setManagementSpecVersion(runtimeBean.getManagementSpecVersion());

    runtimeBean.getInputArguments().forEach(builder::addInputArguments);

    return builder.build();
  }

  private Hardware collectHardwareInfo() {
    String model = System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "Unknown";
    String manufacturer = "Unknown";
    String serialNumber = "Unknown";
    String uuid = "Unknown";

    // Try to get hardware info from system files (Linux)
    try {
      if (System.getProperty("os.name").toLowerCase().contains("linux")) {
        model = readFileContent("/sys/devices/virtual/dmi/id/product_name", model);
        manufacturer = readFileContent("/sys/devices/virtual/dmi/id/sys_vendor", manufacturer);
        serialNumber = readFileContent("/sys/devices/virtual/dmi/id/product_serial", serialNumber);
        uuid = readFileContent("/sys/devices/virtual/dmi/id/product_uuid", uuid);
      }
    } catch (Exception e) {
      log.debug("Could not read hardware info from system files", e);
    }

    return Hardware.newBuilder()
        .setModel(model)
        .setManufacturer(manufacturer)
        .setSerialNumber(serialNumber)
        .setUuid(uuid)
        .setProcessor(collectProcessorInfo())
        .build();
  }

  private Processor collectProcessorInfo() {
    OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

    String processorName = System.getenv("PROCESSOR_IDENTIFIER");
    if (processorName == null) {
      processorName = readFileContent("/proc/cpuinfo", "Unknown");
      if (!"Unknown".equals(processorName)) {
        processorName =
            Arrays.stream(processorName.split("\n"))
                .filter(line -> line.startsWith("model name"))
                .findFirst()
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .orElse("Unknown");
      }
    }

    return Processor.newBuilder()
        .setName(processorName)
        .setIdentifier(System.getProperty("java.vm.name", ""))
        .setArchitecture(System.getProperty("os.arch", ""))
        .setPhysicalProcessorCount(osBean.getAvailableProcessors())
        .setLogicalProcessorCount(Runtime.getRuntime().availableProcessors())
        .setMaxFrequency(-1L)
        .setCpu64Bit(
            System.getProperty("os.arch", "")
                .contains("64")) // Changed from setCpu64bit to setCpu64Bit
        .build();
  }

  private Memory collectMemoryInfo() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

    Memory.Builder builder =
        Memory.newBuilder()
            .setHeapMemoryUsed(heapUsage.getUsed())
            .setHeapMemoryMax(heapUsage.getMax())
            .setHeapMemoryCommitted(heapUsage.getCommitted())
            .setNonHeapMemoryUsed(nonHeapUsage.getUsed())
            .setNonHeapMemoryMax(nonHeapUsage.getMax())
            .setNonHeapMemoryCommitted(nonHeapUsage.getCommitted());

    ManagementFactory.getMemoryPoolMXBeans()
        .forEach(
            pool -> {
              MemoryUsage usage = pool.getUsage();
              builder.addMemoryPools(
                  MemoryPool.newBuilder()
                      .setName(pool.getName())
                      .setType(pool.getType().toString())
                      .setUsed(usage.getUsed())
                      .setMax(usage.getMax())
                      .setCommitted(usage.getCommitted())
                      .build());
            });

    return builder.build();
  }

  private List<Storage> collectStorageInfo() {
    List<Storage> storageList = new ArrayList<>();

    File[] roots = File.listRoots();
    for (File root : roots) {
      storageList.add(
          Storage.newBuilder()
              .setName(root.getAbsolutePath())
              .setType("disk")
              .setModel("Unknown")
              .setSerialNumber("Unknown")
              .setSize(root.getTotalSpace())
              .build());
    }

    return storageList;
  }

  private Container collectContainerInfo() {
    boolean isContainer = detectContainer();
    String containerType = detectContainerType();
    String containerId = getContainerId();
    String containerName = System.getenv("HOSTNAME");
    String imageName = getImageName();

    Container.Builder builder =
        Container.newBuilder()
            .setIsContainer(isContainer)
            .setContainerType(containerType)
            .setContainerId(containerId)
            .setContainerName(containerName != null ? containerName : "")
            .setImageName(imageName);

    Map<String, String> resourceLimits = new HashMap<>();
    Long memoryLimit =
        parseMemoryLimit(readFileContent("/sys/fs/cgroup/memory/memory.limit_in_bytes", null));
    if (memoryLimit != null) {
      resourceLimits.put("memory_limit", String.valueOf(memoryLimit));
    }

    Long cpuLimit = parseCpuLimit(readFileContent("/sys/fs/cgroup/cpu/cpu.cfs_quota_us", null));
    if (cpuLimit != null) {
      resourceLimits.put("cpu_limit", String.valueOf(cpuLimit));
    }

    String cpuRequest = System.getenv("CPU_REQUEST");
    if (cpuRequest != null) {
      resourceLimits.put("cpu_request", cpuRequest);
    }

    String memoryRequest = System.getenv("MEMORY_REQUEST");
    if (memoryRequest != null) {
      resourceLimits.put("memory_request", memoryRequest);
    }

    builder.putAllResourceLimits(resourceLimits);

    return builder.build();
  }

  private boolean detectContainer() {
    if (Files.exists(Paths.get("/.dockerenv"))) {
      return true;
    }

    String cgroupContent = readFileContent("/proc/1/cgroup", "");
    return DOCKER_PATTERN.matcher(cgroupContent).find()
        || KUBERNETES_PATTERN.matcher(cgroupContent).find();
  }

  private String detectContainerType() {
    if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
      return "kubernetes";
    }
    if (Files.exists(Paths.get("/.dockerenv"))) {
      return "docker";
    }

    String cgroupContent = readFileContent("/proc/1/cgroup", "");
    if (KUBERNETES_PATTERN.matcher(cgroupContent).find()) {
      return "kubernetes";
    }
    if (DOCKER_PATTERN.matcher(cgroupContent).find()) {
      return "docker";
    }

    return "unknown";
  }

  private String getContainerId() {
    String cgroupContent = readFileContent("/proc/self/cgroup", "");
    if (!cgroupContent.isEmpty()) {
      String[] lines = cgroupContent.split("\n");
      for (String line : lines) {
        if (line.contains("docker") || line.contains("containerd")) {
          String[] parts = line.split("/");
          if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            if (lastPart.length() >= 12) {
              return lastPart.substring(0, 12);
            }
          }
        }
      }
    }
    return System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "";
  }

  private String getImageName() {
    String[] imageEnvVars = {
      "IMAGE_NAME", "DOCKER_IMAGE", "CONTAINER_IMAGE", "POD_CONTAINER_IMAGE"
    };

    for (String envVar : imageEnvVars) {
      String value = System.getenv(envVar);
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }

    return "unknown";
  }

  private Map<String, String> collectKubernetesLabels() {
    Map<String, String> labels = new HashMap<>();
    System.getenv().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("LABEL_"))
        .forEach(entry -> labels.put(entry.getKey().substring(6).toLowerCase(), entry.getValue()));
    return labels;
  }

  private Map<String, String> collectKubernetesAnnotations() {
    Map<String, String> annotations = new HashMap<>();
    System.getenv().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("ANNOTATION_"))
        .forEach(
            entry -> annotations.put(entry.getKey().substring(11).toLowerCase(), entry.getValue()));
    return annotations;
  }

  private Long parseMemoryLimit(String content) {
    if (content == null || content.trim().isEmpty()) {
      return null;
    }
    try {
      long limit = Long.parseLong(content.trim());
      return limit > (1024L * 1024L * 1024L * 1024L) ? null : limit;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Long parseCpuLimit(String content) {
    if (content == null || content.trim().isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(content.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private EnvironmentVariables collectEnvironmentVariables() {
    Set<String> sensitiveKeys =
        Set.of(
            "PASSWORD",
            "SECRET",
            "TOKEN",
            "KEY",
            "CREDENTIAL",
            "API_KEY",
            "ACCESS_TOKEN",
            "PRIVATE_KEY");

    Map<String, String> env = System.getenv();

    return EnvironmentVariables.newBuilder()
        .setLanguage(env.getOrDefault("LANGUAGE", ""))
        .setPath(env.getOrDefault("PATH", ""))
        .setHostname(env.getOrDefault("HOSTNAME", ""))
        .setLcAll(env.getOrDefault("LC_ALL", ""))
        .setJavaHome(env.getOrDefault("JAVA_HOME", ""))
        .setJavaVersion(env.getOrDefault("JAVA_VERSION", ""))
        .setLang(env.getOrDefault("LANG", ""))
        .setHome(env.getOrDefault("HOME", ""))
        .build();
  }

  private SystemProperties collectSystemProperties() {
    Properties props = System.getProperties();

    return SystemProperties.newBuilder()
        // Java Specification
        .setJavaSpecificationVersion(props.getProperty("java.specification.version", ""))
        .setJavaSpecificationVendor(props.getProperty("java.specification.vendor", ""))
        .setJavaSpecificationName(props.getProperty("java.specification.name", ""))
        .setJavaSpecificationMaintenanceVersion(
            props.getProperty("java.specification.maintenance.version", ""))

        // Java Version Info
        .setJavaVersion(props.getProperty("java.version", ""))
        .setJavaVersionDate(props.getProperty("java.version.date", ""))
        .setJavaVendor(props.getProperty("java.vendor", ""))
        .setJavaVendorVersion(props.getProperty("java.vendor.version", ""))
        .setJavaVendorUrl(props.getProperty("java.vendor.url", ""))
        .setJavaVendorUrlBug(props.getProperty("java.vendor.url.bug", ""))

        // Java Runtime
        .setJavaRuntimeName(props.getProperty("java.runtime.name", ""))
        .setJavaRuntimeVersion(props.getProperty("java.runtime.version", ""))
        .setJavaHome(props.getProperty("java.home", ""))
        .setJavaClassPath(props.getProperty("java.class.path", ""))
        .setJavaLibraryPath(props.getProperty("java.library.path", ""))
        .setJavaClassVersion(props.getProperty("java.class.version", ""))

        // Java VM
        .setJavaVmName(props.getProperty("java.vm.name", ""))
        .setJavaVmVersion(props.getProperty("java.vm.version", ""))
        .setJavaVmVendor(props.getProperty("java.vm.vendor", ""))
        .setJavaVmInfo(props.getProperty("java.vm.info", ""))
        .setJavaVmSpecificationVersion(props.getProperty("java.vm.specification.version", ""))
        .setJavaVmSpecificationVendor(props.getProperty("java.vm.specification.vendor", ""))
        .setJavaVmSpecificationName(props.getProperty("java.vm.specification.name", ""))
        .setJavaVmCompressedOopsMode(props.getProperty("java.vm.compressedOopsMode", ""))

        // Operating System
        .setOsName(props.getProperty("os.name", ""))
        .setOsVersion(props.getProperty("os.version", ""))
        .setOsArch(props.getProperty("os.arch", ""))

        // User Info
        .setUserName(props.getProperty("user.name", ""))
        .setUserHome(props.getProperty("user.home", ""))
        .setUserDir(props.getProperty("user.dir", ""))
        .setUserTimezone(props.getProperty("user.timezone", ""))
        .setUserCountry(props.getProperty("user.country", ""))
        .setUserLanguage(props.getProperty("user.language", ""))

        // File System
        .setFileSeparator(props.getProperty("file.separator", ""))
        .setPathSeparator(props.getProperty("path.separator", ""))
        .setLineSeparator(props.getProperty("line.separator", ""))
        .setFileEncoding(props.getProperty("file.encoding", ""))
        .setNativeEncoding(props.getProperty("native.encoding", ""))

        // Sun/Oracle Specific
        .setSunJnuEncoding(props.getProperty("sun.jnu.encoding", ""))
        .setSunArchDataModel(props.getProperty("sun.arch.data.model", ""))
        .setSunJavaLauncher(props.getProperty("sun.java.launcher", ""))
        .setSunBootLibraryPath(props.getProperty("sun.boot.library.path", ""))
        .setSunJavaCommand(props.getProperty("sun.java.command", ""))
        .setSunCpuEndian(props.getProperty("sun.cpu.endian", ""))
        .setSunManagementCompiler(props.getProperty("sun.management.compiler", ""))
        .setSunIoUnicodeEncoding(props.getProperty("sun.io.unicode.encoding", ""))

        // JDK/Debug
        .setJdkDebug(props.getProperty("jdk.debug", ""))
        .setJavaIoTmpdir(props.getProperty("java.io.tmpdir", ""))

        // Application Specific
        .setEnv(props.getProperty("env", ""))
        .setMicronautClassloaderLogging(props.getProperty("micronaut.classloader.logging", ""))

        // Third Party Libraries
        .setIoNettyAllocatorMaxOrder(props.getProperty("io.netty.allocator.maxOrder", ""))
        .setIoNettyProcessId(props.getProperty("io.netty.processId", ""))
        .setIoNettyMachineId(props.getProperty("io.netty.machineId", ""))
        .setComZaxxerHikariPoolNumber(props.getProperty("com.zaxxer.hikari.pool_number", ""))
        .build();
  }

  private String readFileContent(String filePath, String defaultValue) {
    try {
      Path path = Paths.get(filePath);
      if (Files.exists(path)) {
        return Files.readString(path).trim();
      }
    } catch (IOException e) {
      log.debug("Could not read file: " + filePath, e);
    }
    return defaultValue;
  }

  public com.tcn.exile.models.DiagnosticsResult collectSerdeableDiagnostics() {
    printDiagnosticsToTerminal();
    return com.tcn.exile.models.DiagnosticsResult.fromProto(collectSystemDiagnostics());
  }

  /**
   * Common method to collect logs from MemoryAppender using reflection. This eliminates code
   * duplication between different collection methods.
   *
   * @param startTimeMs Start time in milliseconds since epoch, or null for no start limit
   * @param endTimeMs End time in milliseconds since epoch, or null for no end limit
   * @return LogCollectionResult containing logs and success status
   */
  private LogCollectionResult collectLogsFromMemoryAppender(Long startTimeMs, Long endTimeMs) {
    try {
      // Use reflection to access MemoryAppenderInstance.getInstance()
      Class<?> memoryAppenderInstanceClass =
          Class.forName("com.tcn.exile.memlogger.MemoryAppenderInstance");
      Method getInstanceMethod = memoryAppenderInstanceClass.getMethod("getInstance");
      Object memoryAppenderInstance = getInstanceMethod.invoke(null);

      if (memoryAppenderInstance == null) {
        log.warn("MemoryAppender instance is null - no in-memory logs available");
        return LogCollectionResult.success(new ArrayList<>());
      }

      List<String> logs;

      // Check if time filtering is requested
      if (startTimeMs != null && endTimeMs != null) {
        // Use reflection to call getEventsInTimeRange() on the MemoryAppender instance
        Method getEventsInTimeRangeMethod =
            memoryAppenderInstance
                .getClass()
                .getMethod("getEventsInTimeRange", long.class, long.class);
        @SuppressWarnings("unchecked")
        List<String> retrievedLogs =
            (List<String>)
                getEventsInTimeRangeMethod.invoke(memoryAppenderInstance, startTimeMs, endTimeMs);

        logs = retrievedLogs != null ? retrievedLogs : new ArrayList<>();
        log.debug(
            "Retrieved {} logs within time range {} to {}", logs.size(), startTimeMs, endTimeMs);
      } else {
        // Use reflection to call getEventsAsList() on the MemoryAppender instance
        Method getEventsAsListMethod =
            memoryAppenderInstance.getClass().getMethod("getEventsAsList");
        @SuppressWarnings("unchecked")
        List<String> retrievedLogs =
            (List<String>) getEventsAsListMethod.invoke(memoryAppenderInstance);

        logs = retrievedLogs != null ? retrievedLogs : new ArrayList<>();
        log.debug("Retrieved {} logs (no time range specified)", logs.size());
      }

      return LogCollectionResult.success(logs);

    } catch (ClassNotFoundException e) {
      String errorMsg =
          "MemoryAppender classes not found - memory logging not available: " + e.getMessage();
      log.warn(errorMsg);
      return LogCollectionResult.failure(errorMsg);
    } catch (Exception e) {
      String errorMsg =
          "Failed to retrieve logs from memory appender using reflection: " + e.getMessage();
      log.warn(errorMsg);
      return LogCollectionResult.failure(errorMsg);
    }
  }

  /** Helper method to convert protobuf Timestamp to milliseconds since epoch. */
  private long convertTimestampToMs(com.google.protobuf.Timestamp timestamp) {
    return timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1000000;
  }

  /** Helper method to create a protobuf Timestamp from milliseconds since epoch. */
  private com.google.protobuf.Timestamp createTimestamp(long timeMs) {
    return com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(timeMs / 1000)
        .setNanos((int) ((timeMs % 1000) * 1000000))
        .build();
  }

  /**
   * Collects metrics for all HikariCP connection pools in the application.
   *
   * @return List of HikariPoolMetrics objects with data about each connection pool
   */
  private List<HikariPoolMetrics> collectHikariMetrics() {
    log.info("Collecting HikariCP connection pool metrics...");
    List<HikariPoolMetrics> poolMetrics = new ArrayList<>();

    try {
      // Get the platform MBean server
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

      // Search for all HikariCP pools
      Set<ObjectName> hikariPoolNames =
          mBeanServer.queryNames(new ObjectName("com.zaxxer.hikari:type=Pool *"), null);

      if (hikariPoolNames.isEmpty()) {
        log.info("No HikariCP connection pools found.");
        return poolMetrics;
      }

      log.info("Found {} HikariCP connection pool(s)", hikariPoolNames.size());

      // For each pool, collect metrics
      for (ObjectName poolName : hikariPoolNames) {
        String poolNameStr = poolName.getKeyProperty("type").split(" ")[1];
        if (poolNameStr.startsWith("(") && poolNameStr.endsWith(")")) {
          poolNameStr = poolNameStr.substring(1, poolNameStr.length() - 1);
        }

        try {
          // Basic metrics
          int activeConnections = (Integer) mBeanServer.getAttribute(poolName, "ActiveConnections");
          int idleConnections = (Integer) mBeanServer.getAttribute(poolName, "IdleConnections");
          int totalConnections = (Integer) mBeanServer.getAttribute(poolName, "TotalConnections");
          int threadsAwaitingConnection =
              (Integer) mBeanServer.getAttribute(poolName, "ThreadsAwaitingConnection");

          // Create builder for this pool
          HikariPoolMetrics.Builder poolMetricBuilder =
              HikariPoolMetrics.newBuilder()
                  .setPoolName(poolNameStr)
                  .setActiveConnections(activeConnections)
                  .setIdleConnections(idleConnections)
                  .setTotalConnections(totalConnections)
                  .setThreadsAwaitingConnection(threadsAwaitingConnection);

          // Try to get configuration metrics
          try {
            ObjectName configName =
                new ObjectName("com.zaxxer.hikari:type=PoolConfig (" + poolNameStr + ")");
            if (mBeanServer.isRegistered(configName)) {
              long connectionTimeout =
                  (Long) mBeanServer.getAttribute(configName, "ConnectionTimeout");
              long validationTimeout =
                  (Long) mBeanServer.getAttribute(configName, "ValidationTimeout");
              long idleTimeout = (Long) mBeanServer.getAttribute(configName, "IdleTimeout");
              long maxLifetime = (Long) mBeanServer.getAttribute(configName, "MaxLifetime");
              int minimumIdle = (Integer) mBeanServer.getAttribute(configName, "MinimumIdle");
              int maximumPoolSize =
                  (Integer) mBeanServer.getAttribute(configName, "MaximumPoolSize");
              long leakDetectionThreshold =
                  (Long) mBeanServer.getAttribute(configName, "LeakDetectionThreshold");
              String poolName_ = (String) mBeanServer.getAttribute(configName, "PoolName");

              // Build pool config
              HikariPoolMetrics.PoolConfig.Builder configBuilder =
                  HikariPoolMetrics.PoolConfig.newBuilder()
                      .setPoolName(poolName_)
                      .setConnectionTimeout(connectionTimeout)
                      .setValidationTimeout(validationTimeout)
                      .setIdleTimeout(idleTimeout)
                      .setMaxLifetime(maxLifetime)
                      .setMinimumIdle(minimumIdle)
                      .setMaximumPoolSize(maximumPoolSize)
                      .setLeakDetectionThreshold(leakDetectionThreshold);

              // Try to get JDBC URL and username
              try {
                String jdbcUrl = (String) mBeanServer.getAttribute(configName, "JdbcUrl");
                String username = (String) mBeanServer.getAttribute(configName, "Username");
                configBuilder.setJdbcUrl(jdbcUrl).setUsername(username);
              } catch (Exception e) {
                log.debug("JDBC URL and Username not accessible via JMX: {}", e.getMessage());
              }

              poolMetricBuilder.setPoolConfig(configBuilder);
            }
          } catch (Exception e) {
            log.debug("Error accessing pool configuration: {}", e.getMessage());
          }

          // Collect extended metrics if available
          Map<String, String> extendedMetrics = new HashMap<>();
          try {
            Set<ObjectName> metricNames =
                mBeanServer.queryNames(
                    new ObjectName("metrics:name=hikaricp." + poolNameStr + ".*"), null);

            for (ObjectName metricName : metricNames) {
              String metricType = metricName.getKeyProperty("name");
              try {
                Object value = mBeanServer.getAttribute(metricName, "Value");
                extendedMetrics.put(metricType, String.valueOf(value));
              } catch (Exception e) {
                log.debug("Could not get metric value for {}: {}", metricType, e.getMessage());
              }
            }
          } catch (Exception e) {
            log.debug("Error accessing Dropwizard metrics: {}", e.getMessage());
          }

          poolMetricBuilder.putAllExtendedMetrics(extendedMetrics);
          poolMetrics.add(poolMetricBuilder.build());

        } catch (Exception e) {
          log.error("Error getting metrics for pool {}: {}", poolNameStr, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error("Error collecting HikariCP metrics", e);
    }

    return poolMetrics;
  }

  /**
   * Collects configuration details from the config file.
   *
   * @return ConfigDetails object with API endpoint and certificate information
   */
  private ConfigDetails collectConfigDetails() {
    log.info("Collecting configuration file details...");
    ConfigDetails.Builder configBuilder = ConfigDetails.newBuilder();

    // Define the config paths and filename
    final String CONFIG_FILE_NAME = "com.tcn.exiles.sati.config.cfg";
    final List<Path> watchList = List.of(Path.of("/workdir/config"), Path.of("workdir/config"));

    try {
      // Find the first valid directory
      Optional<Path> configDir =
          watchList.stream().filter(path -> path.toFile().exists()).findFirst();

      if (configDir.isEmpty()) {
        log.error("No valid config directory found");
        return configBuilder.build();
      }

      Path configFile = configDir.get().resolve(CONFIG_FILE_NAME);
      if (!configFile.toFile().exists()) {
        log.error("Config file not found: {}", configFile);
        return configBuilder.build();
      }

      log.info("Found config file: {}", configFile);
      byte[] base64EncodedData = Files.readAllBytes(configFile);
      String dataString = new String(base64EncodedData);

      // Trim and ensure proper base64 length
      byte[] data = dataString.trim().getBytes();
      byte[] decodedData;

      try {
        decodedData = Base64.getDecoder().decode(data);
      } catch (IllegalArgumentException e) {
        // Try removing the last byte which might be a newline
        log.debug("Failed to decode base64, trying with truncated data");
        decodedData = Base64.getDecoder().decode(Arrays.copyOf(data, data.length - 1));
      }

      // Convert to string for processing
      String jsonString = new String(decodedData);

      // Extract JSON values
      String apiEndpoint = extractJsonValue(jsonString, "api_endpoint");
      String certificateName = extractJsonValue(jsonString, "certificate_name");
      String certificateDescription = extractJsonValue(jsonString, "certificate_description");

      // Build Config Details
      configBuilder
          .setApiEndpoint(apiEndpoint)
          .setCertificateName(certificateName)
          .setCertificateDescription(certificateDescription);

    } catch (IOException e) {
      log.error("Error reading or parsing config file", e);
    } catch (Exception e) {
      log.error("Unexpected error processing config file", e);
    }

    return configBuilder.build();
  }

  /**
   * Simple method to extract a JSON value by key from a JSON string. This is a basic implementation
   * that works for first-level string values in JSON.
   */
  private String extractJsonValue(String jsonString, String key) {
    String searchPattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(searchPattern);
    java.util.regex.Matcher matcher = pattern.matcher(jsonString);

    if (matcher.find()) {
      return matcher.group(1);
    }
    return "Not found";
  }

  /**
   * Get JDBC connection details for diagnostic purposes.
   *
   * @return Map containing JDBC connection information
   */
  public Map<String, Object> getJdbcConnectionDetails() {
    Map<String, Object> jdbcDetails = new HashMap<>();

    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      Set<ObjectName> hikariPoolNames =
          mBeanServer.queryNames(new ObjectName("com.zaxxer.hikari:type=Pool *"), null);

      for (ObjectName poolName : hikariPoolNames) {
        String poolNameStr = poolName.getKeyProperty("type").split(" ")[1];
        if (poolNameStr.startsWith("(") && poolNameStr.endsWith(")")) {
          poolNameStr = poolNameStr.substring(1, poolNameStr.length() - 1);
        }

        Map<String, Object> poolInfo = new HashMap<>();

        // Get basic connection metrics
        poolInfo.put("activeConnections", mBeanServer.getAttribute(poolName, "ActiveConnections"));
        poolInfo.put("idleConnections", mBeanServer.getAttribute(poolName, "IdleConnections"));
        poolInfo.put("totalConnections", mBeanServer.getAttribute(poolName, "TotalConnections"));

        // Get configuration details
        try {
          ObjectName configName =
              new ObjectName("com.zaxxer.hikari:type=PoolConfig (" + poolNameStr + ")");
          if (mBeanServer.isRegistered(configName)) {
            poolInfo.put("jdbcUrl", mBeanServer.getAttribute(configName, "JdbcUrl"));
            poolInfo.put("username", mBeanServer.getAttribute(configName, "Username"));

            // Try to get driver class name
            try {
              poolInfo.put(
                  "driverClassName", mBeanServer.getAttribute(configName, "DriverClassName"));
            } catch (Exception e) {
              log.debug("Driver class name not available: {}", e.getMessage());
              poolInfo.put("driverClassName", "Unknown");
            }
          }
        } catch (Exception e) {
          log.debug("Error accessing pool configuration: {}", e.getMessage());
        }

        jdbcDetails.put(poolNameStr, poolInfo);
      }
    } catch (Exception e) {
      log.error("Error collecting JDBC connection details", e);
    }

    return jdbcDetails;
  }

  /**
   * Get event stream statistics.
   *
   * @return Map containing event stream stats
   */
  public Map<String, Object> getEventStreamStats() {
    Map<String, Object> eventStreamStats = new HashMap<>();

    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

      // Check for event stream MBeans
      Set<ObjectName> eventStreamMBeans =
          mBeanServer.queryNames(new ObjectName("com.tcn.exile:type=EventStream,*"), null);

      if (eventStreamMBeans.isEmpty()) {
        // Try alternative MBean domain pattern
        eventStreamMBeans = mBeanServer.queryNames(new ObjectName("exile.eventstream:*"), null);
      }

      if (!eventStreamMBeans.isEmpty()) {
        for (ObjectName streamName : eventStreamMBeans) {
          Map<String, Object> streamInfo = new HashMap<>();

          // Get basic stream information
          try {
            streamInfo.put("status", mBeanServer.getAttribute(streamName, "Status"));
          } catch (Exception e) {
            streamInfo.put("status", "Unknown");
          }

          try {
            streamInfo.put("maxJobs", mBeanServer.getAttribute(streamName, "MaxJobs"));
          } catch (Exception e) {
            streamInfo.put("maxJobs", -1);
          }

          try {
            streamInfo.put("runningJobs", mBeanServer.getAttribute(streamName, "RunningJobs"));
          } catch (Exception e) {
            streamInfo.put("runningJobs", -1);
          }

          try {
            streamInfo.put("completedJobs", mBeanServer.getAttribute(streamName, "CompletedJobs"));
          } catch (Exception e) {
            streamInfo.put("completedJobs", -1);
          }

          try {
            streamInfo.put("queuedJobs", mBeanServer.getAttribute(streamName, "QueuedJobs"));
          } catch (Exception e) {
            streamInfo.put("queuedJobs", -1);
          }

          String streamKey =
              streamName.getKeyProperty("name") != null
                  ? streamName.getKeyProperty("name")
                  : "defaultStream";
          eventStreamStats.put(streamKey, streamInfo);
        }
      } else {
        log.info("No event stream MBeans found");
        eventStreamStats.put("status", "Not available");
      }
    } catch (Exception e) {
      log.error("Error collecting event stream stats", e);
      eventStreamStats.put("error", e.getMessage());
    }

    return eventStreamStats;
  }

  /** Outputs JDBC connection details and event stream statistics to the terminal. */
  public void printDiagnosticsToTerminal() {
    // Get JDBC connection details
    Map<String, Object> jdbcDetails = getJdbcConnectionDetails();

    System.out.println("\n========== JDBC CONNECTION DETAILS ==========");
    if (jdbcDetails.isEmpty()) {
      System.out.println("No JDBC connections found.");
    } else {
      jdbcDetails.forEach(
          (poolName, details) -> {
            System.out.println("\nPool: " + poolName);
            @SuppressWarnings("unchecked")
            Map<String, Object> poolInfo = (Map<String, Object>) details;

            System.out.println("  JDBC URL: " + poolInfo.getOrDefault("jdbcUrl", "Unknown"));
            System.out.println("  Driver: " + poolInfo.getOrDefault("driverClassName", "Unknown"));
            System.out.println("  Username: " + poolInfo.getOrDefault("username", "Unknown"));
            System.out.println(
                "  Total Connections: " + poolInfo.getOrDefault("totalConnections", -1));
            System.out.println(
                "  Active Connections: " + poolInfo.getOrDefault("activeConnections", -1));
            System.out.println(
                "  Idle Connections: " + poolInfo.getOrDefault("idleConnections", -1));
          });
    }

    // Get event stream stats
    Map<String, Object> eventStreamStats = getEventStreamStats();

    System.out.println("\n========== EVENT STREAM STATISTICS ==========");
    if (eventStreamStats.isEmpty() || eventStreamStats.containsKey("status")) {
      System.out.println("No event streams found or not available.");
    } else {
      eventStreamStats.forEach(
          (streamName, stats) -> {
            System.out.println("\nStream: " + streamName);
            @SuppressWarnings("unchecked")
            Map<String, Object> streamInfo = (Map<String, Object>) stats;

            System.out.println("  Status: " + streamInfo.getOrDefault("status", "Unknown"));
            System.out.println("  Max Jobs: " + streamInfo.getOrDefault("maxJobs", -1));
            System.out.println("  Running Jobs: " + streamInfo.getOrDefault("runningJobs", -1));
            System.out.println("  Completed Jobs: " + streamInfo.getOrDefault("completedJobs", -1));
            System.out.println("  Queued Jobs: " + streamInfo.getOrDefault("queuedJobs", -1));
          });
    }

    System.out.println("\n=============================================");
  }
}
