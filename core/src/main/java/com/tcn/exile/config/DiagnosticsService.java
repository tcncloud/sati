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

import com.tcn.exile.models.SystemDiagnostics;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.lang.management.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DiagnosticsService {
  private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

  private static final Pattern DOCKER_PATTERN =
      Pattern.compile(".*docker.*", Pattern.CASE_INSENSITIVE);
  private static final Pattern KUBERNETES_PATTERN =
      Pattern.compile(".*k8s.*|.*kube.*", Pattern.CASE_INSENSITIVE);

  public SystemDiagnostics collectSystemDiagnostics() {
    log.info("Collecting comprehensive system diagnostics...");

    try {
      return new SystemDiagnostics(
          Instant.now().toString(),
          getHostname(),
          collectOperatingSystemInfo(),
          collectJavaRuntimeInfo(),
          collectHardwareInfo(),
          collectMemoryInfo(),
          collectStorageInfo(),
          collectNetworkInfo(),
          collectContainerInfo(),
          collectEnvironmentVariables(),
          collectSystemProperties());
    } catch (Exception e) {
      log.error("Error collecting system diagnostics", e);
      throw new RuntimeException("Failed to collect system diagnostics", e);
    }
  }

  private String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "unknown";
    }
  }

  private SystemDiagnostics.OperatingSystemInfo collectOperatingSystemInfo() {
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

    return new SystemDiagnostics.OperatingSystemInfo(
        osBean.getName(),
        osBean.getVersion(),
        osBean.getArch(),
        System.getProperty("os.name"),
        osBean.getAvailableProcessors(),
        getSystemUptime(),
        osBean.getSystemLoadAverage(),
        totalPhysicalMemory,
        availablePhysicalMemory,
        totalSwapSpace,
        availableSwapSpace);
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

  private SystemDiagnostics.JavaRuntimeInfo collectJavaRuntimeInfo() {
    RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    return new SystemDiagnostics.JavaRuntimeInfo(
        System.getProperty("java.version"),
        System.getProperty("java.vendor"),
        System.getProperty("java.runtime.name"),
        System.getProperty("java.vm.name"),
        System.getProperty("java.vm.version"),
        System.getProperty("java.vm.vendor"),
        System.getProperty("java.specification.name"),
        System.getProperty("java.specification.version"),
        System.getProperty("java.class.path"),
        System.getProperty("java.library.path"),
        runtimeBean.getInputArguments(),
        runtimeBean.getUptime(),
        runtimeBean.getStartTime(),
        runtimeBean.getManagementSpecVersion());
  }

  private SystemDiagnostics.HardwareInfo collectHardwareInfo() {
    String model = System.getenv("HOSTNAME");
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

    return new SystemDiagnostics.HardwareInfo(
        model, manufacturer, serialNumber, uuid, collectProcessorInfo());
  }

  private SystemDiagnostics.ProcessorInfo collectProcessorInfo() {
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

    return new SystemDiagnostics.ProcessorInfo(
        processorName,
        System.getProperty("java.vm.name"),
        System.getProperty("os.arch"),
        osBean.getAvailableProcessors(),
        Runtime.getRuntime().availableProcessors(),
        -1L,
        System.getProperty("os.arch").contains("64"));
  }

  private SystemDiagnostics.MemoryInfo collectMemoryInfo() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

    List<SystemDiagnostics.MemoryPoolInfo> memoryPools =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .map(
                pool -> {
                  MemoryUsage usage = pool.getUsage();
                  return new SystemDiagnostics.MemoryPoolInfo(
                      pool.getName(),
                      pool.getType().toString(),
                      usage.getUsed(),
                      usage.getMax(),
                      usage.getCommitted());
                })
            .collect(Collectors.toList());

    // List<SystemDiagnostics.GarbageCollectorInfo> gcInfo =
    //     ManagementFactory.getGarbageCollectorMXBeans().stream()
    //         .map(
    //             gc ->
    //                 new SystemDiagnostics.GarbageCollectorInfo(
    //                     gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()))
    //         .collect(Collectors.toList());

    return new SystemDiagnostics.MemoryInfo(
        heapUsage.getUsed(),
        heapUsage.getMax(),
        heapUsage.getCommitted(),
        nonHeapUsage.getUsed(),
        nonHeapUsage.getMax(),
        nonHeapUsage.getCommitted(),
        memoryPools,
        null);
    // gcInfo);
  }

  private List<SystemDiagnostics.StorageInfo> collectStorageInfo() {
    List<SystemDiagnostics.StorageInfo> storageList = new ArrayList<>();

    File[] roots = File.listRoots();
    for (File root : roots) {
      List<SystemDiagnostics.PartitionInfo> partitions = new ArrayList<>();

      partitions.add(
          new SystemDiagnostics.PartitionInfo(
              root.getAbsolutePath(),
              "filesystem",
              root.getTotalSpace(),
              root.getUsableSpace(),
              root.getFreeSpace()));

      storageList.add(
          new SystemDiagnostics.StorageInfo(
              root.getAbsolutePath(),
              "disk",
              "Unknown",
              "Unknown",
              root.getTotalSpace(),
              partitions));
    }

    return storageList;
  }

  private List<SystemDiagnostics.NetworkInfo> collectNetworkInfo() {
    List<SystemDiagnostics.NetworkInfo> networkList = new ArrayList<>();

    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface networkInterface = interfaces.nextElement();

        List<String> ipAddresses =
            Collections.list(networkInterface.getInetAddresses()).stream()
                .map(addr -> addr.getHostAddress())
                .collect(Collectors.toList());

        String macAddress = "Unknown";
        try {
          byte[] mac = networkInterface.getHardwareAddress();
          if (mac != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
              sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
            }
            macAddress = sb.toString();
          }
        } catch (Exception e) {
          log.debug("Could not get MAC address for interface: " + networkInterface.getName(), e);
        }

        networkList.add(
            new SystemDiagnostics.NetworkInfo(
                networkInterface.getName(),
                networkInterface.getDisplayName(),
                macAddress,
                ipAddresses,
                -1L, // Network stats would need additional libraries
                -1L,
                -1L,
                -1L,
                networkInterface.isUp(),
                networkInterface.getMTU()));
      }
    } catch (SocketException e) {
      log.error("Error collecting network information", e);
    }

    return networkList;
  }

  private SystemDiagnostics.ContainerInfo collectContainerInfo() {
    boolean isContainer = detectContainer();
    String containerType = detectContainerType();
    String containerId = getContainerId();
    String containerName = System.getenv("HOSTNAME");
    String imageName = getImageName();
    String namespace = System.getenv("POD_NAMESPACE");
    String podName = System.getenv("POD_NAME");

    Map<String, String> labels = collectKubernetesLabels();
    Map<String, String> annotations = collectKubernetesAnnotations();
    SystemDiagnostics.ResourceLimits limits = collectResourceLimits();

    return new SystemDiagnostics.ContainerInfo(
        isContainer,
        containerType,
        containerId,
        containerName,
        imageName,
        namespace,
        podName,
        labels,
        annotations,
        limits);
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
    return System.getenv("HOSTNAME");
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

  private SystemDiagnostics.ResourceLimits collectResourceLimits() {
    Long memoryLimit =
        parseMemoryLimit(readFileContent("/sys/fs/cgroup/memory/memory.limit_in_bytes", null));
    Long cpuLimit = parseCpuLimit(readFileContent("/sys/fs/cgroup/cpu/cpu.cfs_quota_us", null));

    String cpuRequest = System.getenv("CPU_REQUEST");
    String memoryRequest = System.getenv("MEMORY_REQUEST");

    return new SystemDiagnostics.ResourceLimits(memoryLimit, cpuLimit, cpuRequest, memoryRequest);
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

  private Map<String, String> collectEnvironmentVariables() {
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

    return System.getenv().entrySet().stream()
        .filter(
            entry ->
                sensitiveKeys.stream()
                    .noneMatch(sensitive -> entry.getKey().toUpperCase().contains(sensitive)))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Map<String, String> collectSystemProperties() {
    return System.getProperties().entrySet().stream()
        .collect(
            Collectors.toMap(
                entry -> entry.getKey().toString(), entry -> entry.getValue().toString()));
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
}
