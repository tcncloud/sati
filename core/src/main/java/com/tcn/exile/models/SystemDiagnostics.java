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
package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;

@Serdeable
public record SystemDiagnostics(
    // Metadata
    String timestamp,
    String hostname,

    // Operating System Information
    OperatingSystemInfo operatingSystem,

    // Java Runtime Information
    JavaRuntimeInfo javaRuntime,

    // Hardware Information
    HardwareInfo hardware,

    // Memory Information
    MemoryInfo memory,

    // Storage Information
    List<StorageInfo> storage,

    // Network Information
    List<NetworkInfo> network,

    // Container Information
    ContainerInfo container,

    // Environment Variables and System Properties
    Map<String, String> environmentVariables,
    Map<String, String> systemProperties) {

  @Serdeable
  public record OperatingSystemInfo(
      String name,
      String version,
      String architecture,
      String manufacturer,
      int availableProcessors,
      long systemUptime,
      double systemLoadAverage,
      long totalPhysicalMemory,
      long availablePhysicalMemory,
      long totalSwapSpace,
      long availableSwapSpace) {}

  @Serdeable
  public record JavaRuntimeInfo(
      String version,
      String vendor,
      String runtimeName,
      String vmName,
      String vmVersion,
      String vmVendor,
      String specificationName,
      String specificationVersion,
      String classPath,
      String libraryPath,
      List<String> inputArguments,
      long uptime,
      long startTime,
      String managementSpecVersion) {}

  @Serdeable
  public record HardwareInfo(
      String model,
      String manufacturer,
      String serialNumber,
      String uuid,
      ProcessorInfo processor) {}

  @Serdeable
  public record ProcessorInfo(
      String name,
      String identifier,
      String architecture,
      int physicalProcessorCount,
      int logicalProcessorCount,
      long maxFrequency,
      boolean cpu64bit) {}

  @Serdeable
  public record MemoryInfo(
      long heapMemoryUsed,
      long heapMemoryMax,
      long heapMemoryCommitted,
      long nonHeapMemoryUsed,
      long nonHeapMemoryMax,
      long nonHeapMemoryCommitted,
      List<MemoryPoolInfo> memoryPools,
      List<GarbageCollectorInfo> garbageCollectors) {}

  @Serdeable
  public record MemoryPoolInfo(String name, String type, long used, long max, long committed) {}

  @Serdeable
  public record GarbageCollectorInfo(String name, long collectionCount, long collectionTime) {}

  @Serdeable
  public record StorageInfo(
      String name,
      String type,
      String model,
      String serialNumber,
      long size,
      List<PartitionInfo> partitions) {}

  @Serdeable
  public record PartitionInfo(
      String mountPoint, String type, long totalSpace, long usableSpace, long freeSpace) {}

  @Serdeable
  public record NetworkInfo(
      String name,
      String displayName,
      String macAddress,
      List<String> ipAddresses,
      long bytesReceived,
      long bytesSent,
      long packetsReceived,
      long packetsSent,
      boolean up,
      int mtu) {}

  @Serdeable
  public record ContainerInfo(
      boolean isContainer,
      String containerType,
      String containerId,
      String containerName,
      String imageName,
      String namespace,
      String podName,
      Map<String, String> labels,
      Map<String, String> annotations,
      ResourceLimits resourceLimits) {}

  @Serdeable
  public record ResourceLimits(
      Long memoryLimit, Long cpuLimit, String cpuRequest, String memoryRequest) {}
}
