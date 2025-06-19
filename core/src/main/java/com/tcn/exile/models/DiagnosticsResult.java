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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Serdeable
public class DiagnosticsResult {
  private Instant timestamp;
  private String hostname;
  private OperatingSystem operatingSystem;
  private JavaRuntime javaRuntime;
  private Hardware hardware;
  private Memory memory;
  private List<Storage> storage;
  private Container container;
  private EnvironmentVariables environmentVariables;
  private SystemProperties systemProperties;
  private List<HikariPoolMetrics> hikariPoolMetrics;
  private ConfigDetails configDetails;

  // Getters and setters
  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public String getHostname() {
    return hostname;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public OperatingSystem getOperatingSystem() {
    return operatingSystem;
  }

  public void setOperatingSystem(OperatingSystem operatingSystem) {
    this.operatingSystem = operatingSystem;
  }

  public JavaRuntime getJavaRuntime() {
    return javaRuntime;
  }

  public void setJavaRuntime(JavaRuntime javaRuntime) {
    this.javaRuntime = javaRuntime;
  }

  public Hardware getHardware() {
    return hardware;
  }

  public void setHardware(Hardware hardware) {
    this.hardware = hardware;
  }

  public Memory getMemory() {
    return memory;
  }

  public void setMemory(Memory memory) {
    this.memory = memory;
  }

  public List<Storage> getStorage() {
    return storage;
  }

  public void setStorage(List<Storage> storage) {
    this.storage = storage;
  }

  public Container getContainer() {
    return container;
  }

  public void setContainer(Container container) {
    this.container = container;
  }

  public EnvironmentVariables getEnvironmentVariables() {
    return environmentVariables;
  }

  public void setEnvironmentVariables(EnvironmentVariables environmentVariables) {
    this.environmentVariables = environmentVariables;
  }

  public SystemProperties getSystemProperties() {
    return systemProperties;
  }

  public void setSystemProperties(SystemProperties systemProperties) {
    this.systemProperties = systemProperties;
  }

  public List<HikariPoolMetrics> getHikariPoolMetrics() {
    return hikariPoolMetrics;
  }

  public void setHikariPoolMetrics(List<HikariPoolMetrics> hikariPoolMetrics) {
    this.hikariPoolMetrics = hikariPoolMetrics;
  }

  public ConfigDetails getConfigDetails() {
    return configDetails;
  }

  public void setConfigDetails(ConfigDetails configDetails) {
    this.configDetails = configDetails;
  }

  @Serdeable
  public static class OperatingSystem {
    private String name;
    private String version;
    private String architecture;
    private String manufacturer;
    private int availableProcessors;
    private long systemUptime;
    private double systemLoadAverage;
    private long totalPhysicalMemory;
    private long availablePhysicalMemory;
    private long totalSwapSpace;
    private long availableSwapSpace;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
    }

    public String getArchitecture() {
      return architecture;
    }

    public void setArchitecture(String architecture) {
      this.architecture = architecture;
    }

    public String getManufacturer() {
      return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
      this.manufacturer = manufacturer;
    }

    public int getAvailableProcessors() {
      return availableProcessors;
    }

    public void setAvailableProcessors(int availableProcessors) {
      this.availableProcessors = availableProcessors;
    }

    public long getSystemUptime() {
      return systemUptime;
    }

    public void setSystemUptime(long systemUptime) {
      this.systemUptime = systemUptime;
    }

    public double getSystemLoadAverage() {
      return systemLoadAverage;
    }

    public void setSystemLoadAverage(double systemLoadAverage) {
      this.systemLoadAverage = systemLoadAverage;
    }

    public long getTotalPhysicalMemory() {
      return totalPhysicalMemory;
    }

    public void setTotalPhysicalMemory(long totalPhysicalMemory) {
      this.totalPhysicalMemory = totalPhysicalMemory;
    }

    public long getAvailablePhysicalMemory() {
      return availablePhysicalMemory;
    }

    public void setAvailablePhysicalMemory(long availablePhysicalMemory) {
      this.availablePhysicalMemory = availablePhysicalMemory;
    }

    public long getTotalSwapSpace() {
      return totalSwapSpace;
    }

    public void setTotalSwapSpace(long totalSwapSpace) {
      this.totalSwapSpace = totalSwapSpace;
    }

    public long getAvailableSwapSpace() {
      return availableSwapSpace;
    }

    public void setAvailableSwapSpace(long availableSwapSpace) {
      this.availableSwapSpace = availableSwapSpace;
    }
  }

  @Serdeable
  public static class JavaRuntime {
    private String version;
    private String vendor;
    private String runtimeName;
    private String vmName;
    private String vmVersion;
    private String vmVendor;
    private String specificationName;
    private String specificationVersion;
    private String classPath;
    private String libraryPath;
    private List<String> inputArguments;
    private long uptime;
    private long startTime;
    private String managementSpecVersion;

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
    }

    public String getVendor() {
      return vendor;
    }

    public void setVendor(String vendor) {
      this.vendor = vendor;
    }

    public String getRuntimeName() {
      return runtimeName;
    }

    public void setRuntimeName(String runtimeName) {
      this.runtimeName = runtimeName;
    }

    public String getVmName() {
      return vmName;
    }

    public void setVmName(String vmName) {
      this.vmName = vmName;
    }

    public String getVmVersion() {
      return vmVersion;
    }

    public void setVmVersion(String vmVersion) {
      this.vmVersion = vmVersion;
    }

    public String getVmVendor() {
      return vmVendor;
    }

    public void setVmVendor(String vmVendor) {
      this.vmVendor = vmVendor;
    }

    public String getSpecificationName() {
      return specificationName;
    }

    public void setSpecificationName(String specificationName) {
      this.specificationName = specificationName;
    }

    public String getSpecificationVersion() {
      return specificationVersion;
    }

    public void setSpecificationVersion(String specificationVersion) {
      this.specificationVersion = specificationVersion;
    }

    public String getClassPath() {
      return classPath;
    }

    public void setClassPath(String classPath) {
      this.classPath = classPath;
    }

    public String getLibraryPath() {
      return libraryPath;
    }

    public void setLibraryPath(String libraryPath) {
      this.libraryPath = libraryPath;
    }

    public List<String> getInputArguments() {
      return inputArguments;
    }

    public void setInputArguments(List<String> inputArguments) {
      this.inputArguments = inputArguments;
    }

    public long getUptime() {
      return uptime;
    }

    public void setUptime(long uptime) {
      this.uptime = uptime;
    }

    public long getStartTime() {
      return startTime;
    }

    public void setStartTime(long startTime) {
      this.startTime = startTime;
    }

    public String getManagementSpecVersion() {
      return managementSpecVersion;
    }

    public void setManagementSpecVersion(String managementSpecVersion) {
      this.managementSpecVersion = managementSpecVersion;
    }
  }

  @Serdeable
  public static class Hardware {
    private String model;
    private String manufacturer;
    private String serialNumber;
    private String uuid;
    private Processor processor;

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getManufacturer() {
      return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
      this.manufacturer = manufacturer;
    }

    public String getSerialNumber() {
      return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
      this.serialNumber = serialNumber;
    }

    public String getUuid() {
      return uuid;
    }

    public void setUuid(String uuid) {
      this.uuid = uuid;
    }

    public Processor getProcessor() {
      return processor;
    }

    public void setProcessor(Processor processor) {
      this.processor = processor;
    }
  }

  @Serdeable
  public static class Processor {
    private String name;
    private String identifier;
    private String architecture;
    private int physicalProcessorCount;
    private int logicalProcessorCount;
    private long maxFrequency;
    private boolean cpu64bit;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getIdentifier() {
      return identifier;
    }

    public void setIdentifier(String identifier) {
      this.identifier = identifier;
    }

    public String getArchitecture() {
      return architecture;
    }

    public void setArchitecture(String architecture) {
      this.architecture = architecture;
    }

    public int getPhysicalProcessorCount() {
      return physicalProcessorCount;
    }

    public void setPhysicalProcessorCount(int physicalProcessorCount) {
      this.physicalProcessorCount = physicalProcessorCount;
    }

    public int getLogicalProcessorCount() {
      return logicalProcessorCount;
    }

    public void setLogicalProcessorCount(int logicalProcessorCount) {
      this.logicalProcessorCount = logicalProcessorCount;
    }

    public long getMaxFrequency() {
      return maxFrequency;
    }

    public void setMaxFrequency(long maxFrequency) {
      this.maxFrequency = maxFrequency;
    }

    public boolean isCpu64bit() {
      return cpu64bit;
    }

    public void setCpu64bit(boolean cpu64bit) {
      this.cpu64bit = cpu64bit;
    }
  }

  @Serdeable
  public static class Memory {
    private long heapMemoryUsed;
    private long heapMemoryMax;
    private long heapMemoryCommitted;
    private long nonHeapMemoryUsed;
    private long nonHeapMemoryMax;
    private long nonHeapMemoryCommitted;
    private List<MemoryPool> memoryPools;

    public long getHeapMemoryUsed() {
      return heapMemoryUsed;
    }

    public void setHeapMemoryUsed(long heapMemoryUsed) {
      this.heapMemoryUsed = heapMemoryUsed;
    }

    public long getHeapMemoryMax() {
      return heapMemoryMax;
    }

    public void setHeapMemoryMax(long heapMemoryMax) {
      this.heapMemoryMax = heapMemoryMax;
    }

    public long getHeapMemoryCommitted() {
      return heapMemoryCommitted;
    }

    public void setHeapMemoryCommitted(long heapMemoryCommitted) {
      this.heapMemoryCommitted = heapMemoryCommitted;
    }

    public long getNonHeapMemoryUsed() {
      return nonHeapMemoryUsed;
    }

    public void setNonHeapMemoryUsed(long nonHeapMemoryUsed) {
      this.nonHeapMemoryUsed = nonHeapMemoryUsed;
    }

    public long getNonHeapMemoryMax() {
      return nonHeapMemoryMax;
    }

    public void setNonHeapMemoryMax(long nonHeapMemoryMax) {
      this.nonHeapMemoryMax = nonHeapMemoryMax;
    }

    public long getNonHeapMemoryCommitted() {
      return nonHeapMemoryCommitted;
    }

    public void setNonHeapMemoryCommitted(long nonHeapMemoryCommitted) {
      this.nonHeapMemoryCommitted = nonHeapMemoryCommitted;
    }

    public List<MemoryPool> getMemoryPools() {
      return memoryPools;
    }

    public void setMemoryPools(List<MemoryPool> memoryPools) {
      this.memoryPools = memoryPools;
    }
  }

  @Serdeable
  public static class MemoryPool {
    private String name;
    private String type;
    private long used;
    private long max;
    private long committed;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public long getUsed() {
      return used;
    }

    public void setUsed(long used) {
      this.used = used;
    }

    public long getMax() {
      return max;
    }

    public void setMax(long max) {
      this.max = max;
    }

    public long getCommitted() {
      return committed;
    }

    public void setCommitted(long committed) {
      this.committed = committed;
    }
  }

  @Serdeable
  public static class Storage {
    private String name;
    private String type;
    private String model;
    private String serialNumber;
    private long size;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getSerialNumber() {
      return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
      this.serialNumber = serialNumber;
    }

    public long getSize() {
      return size;
    }

    public void setSize(long size) {
      this.size = size;
    }
  }

  @Serdeable
  public static class Container {
    private boolean isContainer;
    private String containerType;
    private String containerId;
    private String containerName;
    private String imageName;
    private Map<String, String> resourceLimits;

    public boolean isContainer() {
      return isContainer;
    }

    public void setContainer(boolean isContainer) {
      this.isContainer = isContainer;
    }

    public String getContainerType() {
      return containerType;
    }

    public void setContainerType(String containerType) {
      this.containerType = containerType;
    }

    public String getContainerId() {
      return containerId;
    }

    public void setContainerId(String containerId) {
      this.containerId = containerId;
    }

    public String getContainerName() {
      return containerName;
    }

    public void setContainerName(String containerName) {
      this.containerName = containerName;
    }

    public String getImageName() {
      return imageName;
    }

    public void setImageName(String imageName) {
      this.imageName = imageName;
    }

    public Map<String, String> getResourceLimits() {
      return resourceLimits;
    }

    public void setResourceLimits(Map<String, String> resourceLimits) {
      this.resourceLimits = resourceLimits;
    }
  }

  @Serdeable
  public static class EnvironmentVariables {
    private String language;
    private String path;
    private String hostname;
    private String lcAll;
    private String javaHome;
    private String javaVersion;
    private String lang;
    private String home;

    public String getLanguage() {
      return language;
    }

    public void setLanguage(String language) {
      this.language = language;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getHostname() {
      return hostname;
    }

    public void setHostname(String hostname) {
      this.hostname = hostname;
    }

    public String getLcAll() {
      return lcAll;
    }

    public void setLcAll(String lcAll) {
      this.lcAll = lcAll;
    }

    public String getJavaHome() {
      return javaHome;
    }

    public void setJavaHome(String javaHome) {
      this.javaHome = javaHome;
    }

    public String getJavaVersion() {
      return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
      this.javaVersion = javaVersion;
    }

    public String getLang() {
      return lang;
    }

    public void setLang(String lang) {
      this.lang = lang;
    }

    public String getHome() {
      return home;
    }

    public void setHome(String home) {
      this.home = home;
    }
  }

  @Serdeable
  public static class SystemProperties {
    private String javaSpecificationVersion;
    private String javaSpecificationVendor;
    private String javaSpecificationName;
    private String javaSpecificationMaintenanceVersion;
    private String javaVersion;
    private String javaVersionDate;
    private String javaVendor;
    private String javaVendorVersion;
    private String javaVendorUrl;
    private String javaVendorUrlBug;
    private String javaRuntimeName;
    private String javaRuntimeVersion;
    private String javaHome;
    private String javaClassPath;
    private String javaLibraryPath;
    private String javaClassVersion;
    private String javaVmName;
    private String javaVmVersion;
    private String javaVmVendor;
    private String javaVmInfo;
    private String javaVmSpecificationVersion;
    private String javaVmSpecificationVendor;
    private String javaVmSpecificationName;
    private String javaVmCompressedOopsMode;
    private String osName;
    private String osVersion;
    private String osArch;
    private String userName;
    private String userHome;
    private String userDir;
    private String userTimezone;
    private String userCountry;
    private String userLanguage;
    private String fileSeparator;
    private String pathSeparator;
    private String lineSeparator;
    private String fileEncoding;
    private String nativeEncoding;
    private String sunJnuEncoding;
    private String sunArchDataModel;
    private String sunJavaLauncher;
    private String sunBootLibraryPath;
    private String sunJavaCommand;
    private String sunCpuEndian;
    private String sunManagementCompiler;
    private String sunIoUnicodeEncoding;
    private String jdkDebug;
    private String javaIoTmpdir;
    private String env;
    private String micronautClassloaderLogging;
    private String ioNettyAllocatorMaxOrder;
    private String ioNettyProcessId;
    private String ioNettyMachineId;
    private String comZaxxerHikariPoolNumber;

    public String getJavaSpecificationVersion() {
      return javaSpecificationVersion;
    }

    public void setJavaSpecificationVersion(String javaSpecificationVersion) {
      this.javaSpecificationVersion = javaSpecificationVersion;
    }

    public String getJavaSpecificationVendor() {
      return javaSpecificationVendor;
    }

    public void setJavaSpecificationVendor(String javaSpecificationVendor) {
      this.javaSpecificationVendor = javaSpecificationVendor;
    }

    public String getJavaSpecificationName() {
      return javaSpecificationName;
    }

    public void setJavaSpecificationName(String javaSpecificationName) {
      this.javaSpecificationName = javaSpecificationName;
    }

    public String getJavaSpecificationMaintenanceVersion() {
      return javaSpecificationMaintenanceVersion;
    }

    public void setJavaSpecificationMaintenanceVersion(String javaSpecificationMaintenanceVersion) {
      this.javaSpecificationMaintenanceVersion = javaSpecificationMaintenanceVersion;
    }

    public String getJavaVersion() {
      return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
      this.javaVersion = javaVersion;
    }

    public String getJavaVersionDate() {
      return javaVersionDate;
    }

    public void setJavaVersionDate(String javaVersionDate) {
      this.javaVersionDate = javaVersionDate;
    }

    public String getJavaVendor() {
      return javaVendor;
    }

    public void setJavaVendor(String javaVendor) {
      this.javaVendor = javaVendor;
    }

    public String getJavaVendorVersion() {
      return javaVendorVersion;
    }

    public void setJavaVendorVersion(String javaVendorVersion) {
      this.javaVendorVersion = javaVendorVersion;
    }

    public String getJavaVendorUrl() {
      return javaVendorUrl;
    }

    public void setJavaVendorUrl(String javaVendorUrl) {
      this.javaVendorUrl = javaVendorUrl;
    }

    public String getJavaVendorUrlBug() {
      return javaVendorUrlBug;
    }

    public void setJavaVendorUrlBug(String javaVendorUrlBug) {
      this.javaVendorUrlBug = javaVendorUrlBug;
    }

    public String getJavaRuntimeName() {
      return javaRuntimeName;
    }

    public void setJavaRuntimeName(String javaRuntimeName) {
      this.javaRuntimeName = javaRuntimeName;
    }

    public String getJavaRuntimeVersion() {
      return javaRuntimeVersion;
    }

    public void setJavaRuntimeVersion(String javaRuntimeVersion) {
      this.javaRuntimeVersion = javaRuntimeVersion;
    }

    public String getJavaHome() {
      return javaHome;
    }

    public void setJavaHome(String javaHome) {
      this.javaHome = javaHome;
    }

    public String getJavaClassPath() {
      return javaClassPath;
    }

    public void setJavaClassPath(String javaClassPath) {
      this.javaClassPath = javaClassPath;
    }

    public String getJavaLibraryPath() {
      return javaLibraryPath;
    }

    public void setJavaLibraryPath(String javaLibraryPath) {
      this.javaLibraryPath = javaLibraryPath;
    }

    public String getJavaClassVersion() {
      return javaClassVersion;
    }

    public void setJavaClassVersion(String javaClassVersion) {
      this.javaClassVersion = javaClassVersion;
    }

    public String getJavaVmName() {
      return javaVmName;
    }

    public void setJavaVmName(String javaVmName) {
      this.javaVmName = javaVmName;
    }

    public String getJavaVmVersion() {
      return javaVmVersion;
    }

    public void setJavaVmVersion(String javaVmVersion) {
      this.javaVmVersion = javaVmVersion;
    }

    public String getJavaVmVendor() {
      return javaVmVendor;
    }

    public void setJavaVmVendor(String javaVmVendor) {
      this.javaVmVendor = javaVmVendor;
    }

    public String getJavaVmInfo() {
      return javaVmInfo;
    }

    public void setJavaVmInfo(String javaVmInfo) {
      this.javaVmInfo = javaVmInfo;
    }

    public String getJavaVmSpecificationVersion() {
      return javaVmSpecificationVersion;
    }

    public void setJavaVmSpecificationVersion(String javaVmSpecificationVersion) {
      this.javaVmSpecificationVersion = javaVmSpecificationVersion;
    }

    public String getJavaVmSpecificationVendor() {
      return javaVmSpecificationVendor;
    }

    public void setJavaVmSpecificationVendor(String javaVmSpecificationVendor) {
      this.javaVmSpecificationVendor = javaVmSpecificationVendor;
    }

    public String getJavaVmSpecificationName() {
      return javaVmSpecificationName;
    }

    public void setJavaVmSpecificationName(String javaVmSpecificationName) {
      this.javaVmSpecificationName = javaVmSpecificationName;
    }

    public String getJavaVmCompressedOopsMode() {
      return javaVmCompressedOopsMode;
    }

    public void setJavaVmCompressedOopsMode(String javaVmCompressedOopsMode) {
      this.javaVmCompressedOopsMode = javaVmCompressedOopsMode;
    }

    public String getOsName() {
      return osName;
    }

    public void setOsName(String osName) {
      this.osName = osName;
    }

    public String getOsVersion() {
      return osVersion;
    }

    public void setOsVersion(String osVersion) {
      this.osVersion = osVersion;
    }

    public String getOsArch() {
      return osArch;
    }

    public void setOsArch(String osArch) {
      this.osArch = osArch;
    }

    public String getUserName() {
      return userName;
    }

    public void setUserName(String userName) {
      this.userName = userName;
    }

    public String getUserHome() {
      return userHome;
    }

    public void setUserHome(String userHome) {
      this.userHome = userHome;
    }

    public String getUserDir() {
      return userDir;
    }

    public void setUserDir(String userDir) {
      this.userDir = userDir;
    }

    public String getUserTimezone() {
      return userTimezone;
    }

    public void setUserTimezone(String userTimezone) {
      this.userTimezone = userTimezone;
    }

    public String getUserCountry() {
      return userCountry;
    }

    public void setUserCountry(String userCountry) {
      this.userCountry = userCountry;
    }

    public String getUserLanguage() {
      return userLanguage;
    }

    public void setUserLanguage(String userLanguage) {
      this.userLanguage = userLanguage;
    }

    public String getFileSeparator() {
      return fileSeparator;
    }

    public void setFileSeparator(String fileSeparator) {
      this.fileSeparator = fileSeparator;
    }

    public String getPathSeparator() {
      return pathSeparator;
    }

    public void setPathSeparator(String pathSeparator) {
      this.pathSeparator = pathSeparator;
    }

    public String getLineSeparator() {
      return lineSeparator;
    }

    public void setLineSeparator(String lineSeparator) {
      this.lineSeparator = lineSeparator;
    }

    public String getFileEncoding() {
      return fileEncoding;
    }

    public void setFileEncoding(String fileEncoding) {
      this.fileEncoding = fileEncoding;
    }

    public String getNativeEncoding() {
      return nativeEncoding;
    }

    public void setNativeEncoding(String nativeEncoding) {
      this.nativeEncoding = nativeEncoding;
    }

    public String getSunJnuEncoding() {
      return sunJnuEncoding;
    }

    public void setSunJnuEncoding(String sunJnuEncoding) {
      this.sunJnuEncoding = sunJnuEncoding;
    }

    public String getSunArchDataModel() {
      return sunArchDataModel;
    }

    public void setSunArchDataModel(String sunArchDataModel) {
      this.sunArchDataModel = sunArchDataModel;
    }

    public String getSunJavaLauncher() {
      return sunJavaLauncher;
    }

    public void setSunJavaLauncher(String sunJavaLauncher) {
      this.sunJavaLauncher = sunJavaLauncher;
    }

    public String getSunBootLibraryPath() {
      return sunBootLibraryPath;
    }

    public void setSunBootLibraryPath(String sunBootLibraryPath) {
      this.sunBootLibraryPath = sunBootLibraryPath;
    }

    public String getSunJavaCommand() {
      return sunJavaCommand;
    }

    public void setSunJavaCommand(String sunJavaCommand) {
      this.sunJavaCommand = sunJavaCommand;
    }

    public String getSunCpuEndian() {
      return sunCpuEndian;
    }

    public void setSunCpuEndian(String sunCpuEndian) {
      this.sunCpuEndian = sunCpuEndian;
    }

    public String getSunManagementCompiler() {
      return sunManagementCompiler;
    }

    public void setSunManagementCompiler(String sunManagementCompiler) {
      this.sunManagementCompiler = sunManagementCompiler;
    }

    public String getSunIoUnicodeEncoding() {
      return sunIoUnicodeEncoding;
    }

    public void setSunIoUnicodeEncoding(String sunIoUnicodeEncoding) {
      this.sunIoUnicodeEncoding = sunIoUnicodeEncoding;
    }

    public String getJdkDebug() {
      return jdkDebug;
    }

    public void setJdkDebug(String jdkDebug) {
      this.jdkDebug = jdkDebug;
    }

    public String getJavaIoTmpdir() {
      return javaIoTmpdir;
    }

    public void setJavaIoTmpdir(String javaIoTmpdir) {
      this.javaIoTmpdir = javaIoTmpdir;
    }

    public String getEnv() {
      return env;
    }

    public void setEnv(String env) {
      this.env = env;
    }

    public String getMicronautClassloaderLogging() {
      return micronautClassloaderLogging;
    }

    public void setMicronautClassloaderLogging(String micronautClassloaderLogging) {
      this.micronautClassloaderLogging = micronautClassloaderLogging;
    }

    public String getIoNettyAllocatorMaxOrder() {
      return ioNettyAllocatorMaxOrder;
    }

    public void setIoNettyAllocatorMaxOrder(String ioNettyAllocatorMaxOrder) {
      this.ioNettyAllocatorMaxOrder = ioNettyAllocatorMaxOrder;
    }

    public String getIoNettyProcessId() {
      return ioNettyProcessId;
    }

    public void setIoNettyProcessId(String ioNettyProcessId) {
      this.ioNettyProcessId = ioNettyProcessId;
    }

    public String getIoNettyMachineId() {
      return ioNettyMachineId;
    }

    public void setIoNettyMachineId(String ioNettyMachineId) {
      this.ioNettyMachineId = ioNettyMachineId;
    }

    public String getComZaxxerHikariPoolNumber() {
      return comZaxxerHikariPoolNumber;
    }

    public void setComZaxxerHikariPoolNumber(String comZaxxerHikariPoolNumber) {
      this.comZaxxerHikariPoolNumber = comZaxxerHikariPoolNumber;
    }
  }

  @Serdeable
  public static class HikariPoolMetrics {
    private String poolName;
    private int activeConnections;
    private int idleConnections;
    private int totalConnections;
    private int threadsAwaitingConnection;
    private PoolConfig poolConfig;
    private Map<String, String> extendedMetrics;

    public String getPoolName() {
      return poolName;
    }

    public void setPoolName(String poolName) {
      this.poolName = poolName;
    }

    public int getActiveConnections() {
      return activeConnections;
    }

    public void setActiveConnections(int activeConnections) {
      this.activeConnections = activeConnections;
    }

    public int getIdleConnections() {
      return idleConnections;
    }

    public void setIdleConnections(int idleConnections) {
      this.idleConnections = idleConnections;
    }

    public int getTotalConnections() {
      return totalConnections;
    }

    public void setTotalConnections(int totalConnections) {
      this.totalConnections = totalConnections;
    }

    public int getThreadsAwaitingConnection() {
      return threadsAwaitingConnection;
    }

    public void setThreadsAwaitingConnection(int threadsAwaitingConnection) {
      this.threadsAwaitingConnection = threadsAwaitingConnection;
    }

    public PoolConfig getPoolConfig() {
      return poolConfig;
    }

    public void setPoolConfig(PoolConfig poolConfig) {
      this.poolConfig = poolConfig;
    }

    public Map<String, String> getExtendedMetrics() {
      return extendedMetrics;
    }

    public void setExtendedMetrics(Map<String, String> extendedMetrics) {
      this.extendedMetrics = extendedMetrics;
    }

    @Serdeable
    public static class PoolConfig {
      private String poolName;
      private long connectionTimeout;
      private long validationTimeout;
      private long idleTimeout;
      private long maxLifetime;
      private int minimumIdle;
      private int maximumPoolSize;
      private long leakDetectionThreshold;
      private String jdbcUrl;
      private String username;

      public String getPoolName() {
        return poolName;
      }

      public void setPoolName(String poolName) {
        this.poolName = poolName;
      }

      public long getConnectionTimeout() {
        return connectionTimeout;
      }

      public void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
      }

      public long getValidationTimeout() {
        return validationTimeout;
      }

      public void setValidationTimeout(long validationTimeout) {
        this.validationTimeout = validationTimeout;
      }

      public long getIdleTimeout() {
        return idleTimeout;
      }

      public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
      }

      public long getMaxLifetime() {
        return maxLifetime;
      }

      public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime;
      }

      public int getMinimumIdle() {
        return minimumIdle;
      }

      public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
      }

      public int getMaximumPoolSize() {
        return maximumPoolSize;
      }

      public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
      }

      public long getLeakDetectionThreshold() {
        return leakDetectionThreshold;
      }

      public void setLeakDetectionThreshold(long leakDetectionThreshold) {
        this.leakDetectionThreshold = leakDetectionThreshold;
      }

      public String getJdbcUrl() {
        return jdbcUrl;
      }

      public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
      }

      public String getUsername() {
        return username;
      }

      public void setUsername(String username) {
        this.username = username;
      }
    }
  }

  @Serdeable
  public static class ConfigDetails {
    private String apiEndpoint;
    private String certificateName;
    private String certificateDescription;

    public String getApiEndpoint() {
      return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
      this.apiEndpoint = apiEndpoint;
    }

    public String getCertificateName() {
      return certificateName;
    }

    public void setCertificateName(String certificateName) {
      this.certificateName = certificateName;
    }

    public String getCertificateDescription() {
      return certificateDescription;
    }

    public void setCertificateDescription(String certificateDescription) {
      this.certificateDescription = certificateDescription;
    }
  }

  // Convert from protobuf DiagnosticsResult to our Serdeable model
  public static DiagnosticsResult fromProto(
      build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.DiagnosticsResult proto) {
    DiagnosticsResult result = new DiagnosticsResult();

    // Set basic fields
    result.setTimestamp(
        Instant.ofEpochSecond(proto.getTimestamp().getSeconds(), proto.getTimestamp().getNanos()));
    result.setHostname(proto.getHostname());

    // Convert OperatingSystem
    if (proto.hasOperatingSystem()) {
      OperatingSystem os = new OperatingSystem();
      os.setName(proto.getOperatingSystem().getName());
      os.setVersion(proto.getOperatingSystem().getVersion());
      os.setArchitecture(proto.getOperatingSystem().getArchitecture());
      os.setManufacturer(proto.getOperatingSystem().getManufacturer());
      os.setAvailableProcessors(proto.getOperatingSystem().getAvailableProcessors());
      os.setSystemUptime(proto.getOperatingSystem().getSystemUptime());
      os.setSystemLoadAverage(proto.getOperatingSystem().getSystemLoadAverage());
      os.setTotalPhysicalMemory(proto.getOperatingSystem().getTotalPhysicalMemory());
      os.setAvailablePhysicalMemory(proto.getOperatingSystem().getAvailablePhysicalMemory());
      os.setTotalSwapSpace(proto.getOperatingSystem().getTotalSwapSpace());
      os.setAvailableSwapSpace(proto.getOperatingSystem().getAvailableSwapSpace());
      result.setOperatingSystem(os);
    }

    // Convert JavaRuntime
    if (proto.hasJavaRuntime()) {
      JavaRuntime runtime = new JavaRuntime();
      runtime.setVersion(proto.getJavaRuntime().getVersion());
      runtime.setVendor(proto.getJavaRuntime().getVendor());
      runtime.setRuntimeName(proto.getJavaRuntime().getRuntimeName());
      runtime.setVmName(proto.getJavaRuntime().getVmName());
      runtime.setVmVersion(proto.getJavaRuntime().getVmVersion());
      runtime.setVmVendor(proto.getJavaRuntime().getVmVendor());
      runtime.setSpecificationName(proto.getJavaRuntime().getSpecificationName());
      runtime.setSpecificationVersion(proto.getJavaRuntime().getSpecificationVersion());
      runtime.setClassPath(proto.getJavaRuntime().getClassPath());
      runtime.setLibraryPath(proto.getJavaRuntime().getLibraryPath());
      runtime.setInputArguments(new ArrayList<>(proto.getJavaRuntime().getInputArgumentsList()));
      runtime.setUptime(proto.getJavaRuntime().getUptime());
      runtime.setStartTime(proto.getJavaRuntime().getStartTime());
      runtime.setManagementSpecVersion(proto.getJavaRuntime().getManagementSpecVersion());
      result.setJavaRuntime(runtime);
    }

    // Convert Hardware
    if (proto.hasHardware()) {
      Hardware hardware = new Hardware();
      hardware.setModel(proto.getHardware().getModel());
      hardware.setManufacturer(proto.getHardware().getManufacturer());
      hardware.setSerialNumber(proto.getHardware().getSerialNumber());
      hardware.setUuid(proto.getHardware().getUuid());

      if (proto.getHardware().hasProcessor()) {
        Processor processor = new Processor();
        processor.setName(proto.getHardware().getProcessor().getName());
        processor.setIdentifier(proto.getHardware().getProcessor().getIdentifier());
        processor.setArchitecture(proto.getHardware().getProcessor().getArchitecture());
        processor.setPhysicalProcessorCount(
            proto.getHardware().getProcessor().getPhysicalProcessorCount());
        processor.setLogicalProcessorCount(
            proto.getHardware().getProcessor().getLogicalProcessorCount());
        processor.setMaxFrequency(proto.getHardware().getProcessor().getMaxFrequency());
        processor.setCpu64bit(proto.getHardware().getProcessor().getCpu64Bit());
        hardware.setProcessor(processor);
      }

      result.setHardware(hardware);
    }

    // Convert Memory
    if (proto.hasMemory()) {
      Memory memory = new Memory();
      memory.setHeapMemoryUsed(proto.getMemory().getHeapMemoryUsed());
      memory.setHeapMemoryMax(proto.getMemory().getHeapMemoryMax());
      memory.setHeapMemoryCommitted(proto.getMemory().getHeapMemoryCommitted());
      memory.setNonHeapMemoryUsed(proto.getMemory().getNonHeapMemoryUsed());
      memory.setNonHeapMemoryMax(proto.getMemory().getNonHeapMemoryMax());
      memory.setNonHeapMemoryCommitted(proto.getMemory().getNonHeapMemoryCommitted());

      List<MemoryPool> pools = new ArrayList<>();
      for (var protoPool : proto.getMemory().getMemoryPoolsList()) {
        MemoryPool pool = new MemoryPool();
        pool.setName(protoPool.getName());
        pool.setType(protoPool.getType());
        pool.setUsed(protoPool.getUsed());
        pool.setMax(protoPool.getMax());
        pool.setCommitted(protoPool.getCommitted());
        pools.add(pool);
      }
      memory.setMemoryPools(pools);

      result.setMemory(memory);
    }

    // Convert Storage
    List<Storage> storageList = new ArrayList<>();
    for (var protoStorage : proto.getStorageList()) {
      Storage storage = new Storage();
      storage.setName(protoStorage.getName());
      storage.setType(protoStorage.getType());
      storage.setModel(protoStorage.getModel());
      storage.setSerialNumber(protoStorage.getSerialNumber());
      storage.setSize(protoStorage.getSize());
      storageList.add(storage);
    }
    result.setStorage(storageList);

    // Convert Container
    if (proto.hasContainer()) {
      Container container = new Container();
      container.setContainer(proto.getContainer().getIsContainer());
      container.setContainerType(proto.getContainer().getContainerType());
      container.setContainerId(proto.getContainer().getContainerId());
      container.setContainerName(proto.getContainer().getContainerName());
      container.setImageName(proto.getContainer().getImageName());
      container.setResourceLimits(new HashMap<>(proto.getContainer().getResourceLimitsMap()));
      result.setContainer(container);
    }

    // Convert EnvironmentVariables
    if (proto.hasEnvironmentVariables()) {
      EnvironmentVariables env = new EnvironmentVariables();
      env.setLanguage(proto.getEnvironmentVariables().getLanguage());
      env.setPath(proto.getEnvironmentVariables().getPath());
      env.setHostname(proto.getEnvironmentVariables().getHostname());
      env.setLcAll(proto.getEnvironmentVariables().getLcAll());
      env.setJavaHome(proto.getEnvironmentVariables().getJavaHome());
      env.setJavaVersion(proto.getEnvironmentVariables().getJavaVersion());
      env.setLang(proto.getEnvironmentVariables().getLang());
      env.setHome(proto.getEnvironmentVariables().getHome());
      result.setEnvironmentVariables(env);
    }

    // Convert SystemProperties
    if (proto.hasSystemProperties()) {
      SystemProperties props = new SystemProperties();
      props.setJavaSpecificationVersion(proto.getSystemProperties().getJavaSpecificationVersion());
      props.setJavaSpecificationVendor(proto.getSystemProperties().getJavaSpecificationVendor());
      props.setJavaSpecificationName(proto.getSystemProperties().getJavaSpecificationName());
      props.setJavaSpecificationMaintenanceVersion(
          proto.getSystemProperties().getJavaSpecificationMaintenanceVersion());
      props.setJavaVersion(proto.getSystemProperties().getJavaVersion());
      props.setJavaVersionDate(proto.getSystemProperties().getJavaVersionDate());
      props.setJavaVendor(proto.getSystemProperties().getJavaVendor());
      props.setJavaVendorVersion(proto.getSystemProperties().getJavaVendorVersion());
      props.setJavaVendorUrl(proto.getSystemProperties().getJavaVendorUrl());
      props.setJavaVendorUrlBug(proto.getSystemProperties().getJavaVendorUrlBug());
      props.setJavaRuntimeName(proto.getSystemProperties().getJavaRuntimeName());
      props.setJavaRuntimeVersion(proto.getSystemProperties().getJavaRuntimeVersion());
      props.setJavaHome(proto.getSystemProperties().getJavaHome());
      props.setJavaClassPath(proto.getSystemProperties().getJavaClassPath());
      props.setJavaLibraryPath(proto.getSystemProperties().getJavaLibraryPath());
      props.setJavaClassVersion(proto.getSystemProperties().getJavaClassVersion());
      props.setJavaVmName(proto.getSystemProperties().getJavaVmName());
      props.setJavaVmVersion(proto.getSystemProperties().getJavaVmVersion());
      props.setJavaVmVendor(proto.getSystemProperties().getJavaVmVendor());
      props.setJavaVmInfo(proto.getSystemProperties().getJavaVmInfo());
      props.setJavaVmSpecificationVersion(
          proto.getSystemProperties().getJavaVmSpecificationVersion());
      props.setJavaVmSpecificationVendor(
          proto.getSystemProperties().getJavaVmSpecificationVendor());
      props.setJavaVmSpecificationName(proto.getSystemProperties().getJavaVmSpecificationName());
      props.setJavaVmCompressedOopsMode(proto.getSystemProperties().getJavaVmCompressedOopsMode());
      props.setOsName(proto.getSystemProperties().getOsName());
      props.setOsVersion(proto.getSystemProperties().getOsVersion());
      props.setOsArch(proto.getSystemProperties().getOsArch());
      props.setUserName(proto.getSystemProperties().getUserName());
      props.setUserHome(proto.getSystemProperties().getUserHome());
      props.setUserDir(proto.getSystemProperties().getUserDir());
      props.setUserTimezone(proto.getSystemProperties().getUserTimezone());
      props.setUserCountry(proto.getSystemProperties().getUserCountry());
      props.setUserLanguage(proto.getSystemProperties().getUserLanguage());
      props.setFileSeparator(proto.getSystemProperties().getFileSeparator());
      props.setPathSeparator(proto.getSystemProperties().getPathSeparator());
      props.setLineSeparator(proto.getSystemProperties().getLineSeparator());
      props.setFileEncoding(proto.getSystemProperties().getFileEncoding());
      props.setNativeEncoding(proto.getSystemProperties().getNativeEncoding());
      props.setSunJnuEncoding(proto.getSystemProperties().getSunJnuEncoding());
      props.setSunArchDataModel(proto.getSystemProperties().getSunArchDataModel());
      props.setSunJavaLauncher(proto.getSystemProperties().getSunJavaLauncher());
      props.setSunBootLibraryPath(proto.getSystemProperties().getSunBootLibraryPath());
      props.setSunJavaCommand(proto.getSystemProperties().getSunJavaCommand());
      props.setSunCpuEndian(proto.getSystemProperties().getSunCpuEndian());
      props.setSunManagementCompiler(proto.getSystemProperties().getSunManagementCompiler());
      props.setSunIoUnicodeEncoding(proto.getSystemProperties().getSunIoUnicodeEncoding());
      props.setJdkDebug(proto.getSystemProperties().getJdkDebug());
      props.setJavaIoTmpdir(proto.getSystemProperties().getJavaIoTmpdir());
      props.setEnv(proto.getSystemProperties().getEnv());
      props.setMicronautClassloaderLogging(
          proto.getSystemProperties().getMicronautClassloaderLogging());
      props.setIoNettyAllocatorMaxOrder(proto.getSystemProperties().getIoNettyAllocatorMaxOrder());
      props.setIoNettyProcessId(proto.getSystemProperties().getIoNettyProcessId());
      props.setIoNettyMachineId(proto.getSystemProperties().getIoNettyMachineId());
      props.setComZaxxerHikariPoolNumber(
          proto.getSystemProperties().getComZaxxerHikariPoolNumber());
      result.setSystemProperties(props);
    }

    // Convert HikariPoolMetrics
    List<HikariPoolMetrics> hikariPools = new ArrayList<>();
    for (var protoPool : proto.getHikariPoolMetricsList()) {
      HikariPoolMetrics pool = new HikariPoolMetrics();
      pool.setPoolName(protoPool.getPoolName());
      pool.setActiveConnections(protoPool.getActiveConnections());
      pool.setIdleConnections(protoPool.getIdleConnections());
      pool.setTotalConnections(protoPool.getTotalConnections());
      pool.setThreadsAwaitingConnection(protoPool.getThreadsAwaitingConnection());
      pool.setExtendedMetrics(new HashMap<>(protoPool.getExtendedMetricsMap()));

      if (protoPool.hasPoolConfig()) {
        HikariPoolMetrics.PoolConfig config = new HikariPoolMetrics.PoolConfig();
        config.setPoolName(protoPool.getPoolConfig().getPoolName());
        config.setConnectionTimeout(protoPool.getPoolConfig().getConnectionTimeout());
        config.setValidationTimeout(protoPool.getPoolConfig().getValidationTimeout());
        config.setIdleTimeout(protoPool.getPoolConfig().getIdleTimeout());
        config.setMaxLifetime(protoPool.getPoolConfig().getMaxLifetime());
        config.setMinimumIdle(protoPool.getPoolConfig().getMinimumIdle());
        config.setMaximumPoolSize(protoPool.getPoolConfig().getMaximumPoolSize());
        config.setLeakDetectionThreshold(protoPool.getPoolConfig().getLeakDetectionThreshold());
        config.setJdbcUrl(protoPool.getPoolConfig().getJdbcUrl());
        config.setUsername(protoPool.getPoolConfig().getUsername());
        pool.setPoolConfig(config);
      }

      hikariPools.add(pool);
    }
    result.setHikariPoolMetrics(hikariPools);

    // Convert ConfigDetails
    if (proto.hasConfigDetails()) {
      ConfigDetails config = new ConfigDetails();
      config.setApiEndpoint(proto.getConfigDetails().getApiEndpoint());
      config.setCertificateName(proto.getConfigDetails().getCertificateName());
      config.setCertificateDescription(proto.getConfigDetails().getCertificateDescription());
      result.setConfigDetails(config);
    }

    return result;
  }
}
