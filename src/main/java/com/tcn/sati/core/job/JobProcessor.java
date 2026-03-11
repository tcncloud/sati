package com.tcn.sati.core.job;

import build.buf.gen.tcnapi.exile.core.v2.Field;
import build.buf.gen.tcnapi.exile.core.v2.Pool;
import build.buf.gen.tcnapi.exile.core.v2.Record;
import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.backend.TenantBackendClient.*;
import com.tcn.sati.infra.gate.GateClient;
import com.tcn.sati.infra.logging.MemoryLogAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processes jobs received from Gate using a thread pool.
 * 
 * Features:
 * - Configurable thread pool size
 * - Job queueing with bounded queue
 * - Automatic result submission to Gate
 * - Error handling and reporting
 */
public class JobProcessor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final TenantBackendClient backendClient;
    private final GateClient gateClient;
    private final ThreadPoolExecutor executor;
    private final BlockingQueue<Runnable> jobQueue;
    private final String appName;
    private final String appVersion;

    private final AtomicLong processedJobs = new AtomicLong(0);
    private final AtomicLong failedJobs = new AtomicLong(0);

    private volatile boolean shuttingDown = false;

    public JobProcessor(TenantBackendClient backendClient, GateClient gateClient) {
        this(backendClient, gateClient, 5, null, null);
    }

    public JobProcessor(TenantBackendClient backendClient, GateClient gateClient,
            int maxWorkers, String appName, String appVersion) {
        this.backendClient = backendClient;
        this.gateClient = gateClient;
        this.appName = appName != null ? appName : "Sati";
        this.appVersion = appVersion != null ? appVersion : "dev";
        this.jobQueue = new LinkedBlockingQueue<>(1000); // Bounded queue
        this.executor = new ThreadPoolExecutor(
                2, // core threads
                maxWorkers, // max threads
                60L, // idle timeout
                TimeUnit.SECONDS,
                jobQueue,
                r -> {
                    Thread t = new Thread(r, "job-worker");
                    t.setDaemon(true);
                    return t;
                });

        log.info("JobProcessor initialized with {} max workers", maxWorkers);
    }

    /**
     * Process a job from Gate.
     */
    public void processJob(StreamJobsResponse job) {
        if (shuttingDown) {
            log.warn("Rejecting job {} - processor is shutting down", job.getJobId());
            submitError(job.getJobId(), "Processor shutting down");
            return;
        }

        // Check if this is an admin job (can run even if backend unavailable)
        boolean isAdminJob = isAdminJob(job);

        if (!isAdminJob && !backendClient.isConnected()) {
            log.warn("Rejecting job {} - backend not connected", job.getJobId());
            submitError(job.getJobId(), "Backend not connected");
            return;
        }

        executor.execute(() -> executeJob(job));
    }

    private void executeJob(StreamJobsResponse job) {
        String jobId = job.getJobId();
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Executing job: {}", jobId);

            if (job.hasListPools()) {
                handleListPools(jobId);

            } else if (job.hasGetPoolStatus()) {
                handleGetPoolStatus(jobId, job.getGetPoolStatus().getPoolId());

            } else if (job.hasGetPoolRecords()) {
                handleGetPoolRecords(jobId, job.getGetPoolRecords().getPoolId());

            } else if (job.hasInfo()) {
                handleInfo(jobId);

            } else if (job.hasDiagnostics()) {
                handleDiagnostics(jobId);

            } else if (job.hasShutdown()) {
                handleShutdown(jobId);

            } else if (job.hasPopAccount()) {
                handlePopAccount(jobId, job.getPopAccount());

            } else if (job.hasSearchRecords()) {
                handleSearchRecords(jobId, job.getSearchRecords());

            } else if (job.hasGetRecordFields()) {
                handleReadFields(jobId, job.getGetRecordFields());

            } else if (job.hasSetRecordFields()) {
                handleWriteFields(jobId, job.getSetRecordFields());

            } else if (job.hasCreatePayment()) {
                handleCreatePayment(jobId, job.getCreatePayment());

            } else if (job.hasExecuteLogic()) {
                handleExecuteLogic(jobId, job.getExecuteLogic());

            } else if (job.hasListTenantLogs()) {
                handleListTenantLogs(jobId, job.getListTenantLogs());

            } else if (job.hasSetLogLevel()) {
                handleSetLogLevel(jobId, job.getSetLogLevel());

            } else if (job.hasLogging()) {
                handleLogging(jobId, job.getLogging());

            } else {
                log.warn("Unknown job type for job: {}", jobId);
                submitError(jobId, "Unknown job type");
                return;
            }

            processedJobs.incrementAndGet();
            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("Job {} completed in {}ms", jobId, elapsed);

        } catch (Exception e) {
            failedJobs.incrementAndGet();
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            submitError(jobId, "Execution error: " + e.getMessage());
        }
    }

    // ========== Job Handlers ==========

    private void handleListPools(String jobId) throws Exception {
        var pools = backendClient.listPools();

        // Build result using the correct Pool protobuf
        var resultBuilder = SubmitJobResultsRequest.ListPoolsResult.newBuilder();

        for (var pool : pools) {
            resultBuilder.addPools(
                    Pool.newBuilder()
                            .setPoolId(pool.id)
                            .setDescription(pool.name)
                            .build());
        }

        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setListPoolsResult(resultBuilder)
                .build();

        submitResult(request);
    }

    private void handleGetPoolStatus(String jobId, String poolId) throws Exception {
        var status = backendClient.getPoolStatus(poolId);

        // GetPoolStatusResult doesn't have direct setters for these fields
        // Submit a pool with record count instead
        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setGetPoolStatusResult(
                        SubmitJobResultsRequest.GetPoolStatusResult.newBuilder()
                                .setPool(Pool.newBuilder()
                                        .setPoolId(poolId)
                                        .setRecordCount(status.totalRecords)
                                        .build())
                                .build())
                .build();

        submitResult(request);
    }

    private void handleGetPoolRecords(String jobId, String poolId) throws Exception {
        int page = 0;
        boolean hasMore = true;

        while (hasMore) {
            var records = backendClient.getPoolRecords(poolId, page);
            hasMore = records != null && !records.isEmpty();

            var resultBuilder = SubmitJobResultsRequest.GetPoolRecordsResult.newBuilder();
            if (hasMore) {
                for (var rec : records) {
                    resultBuilder.addRecords(
                            Record.newBuilder()
                                    .setRecordId(rec.recordId)
                                    .build());
                }
            }

            boolean isLast = !hasMore || records.size() < 100; // assume default page size
            submitResult(SubmitJobResultsRequest.newBuilder()
                    .setJobId(jobId)
                    .setEndOfTransmission(isLast)
                    .setGetPoolRecordsResult(resultBuilder)
                    .build());

            if (isLast) break;
            page++;
        }
    }

    private void handleInfo(String jobId) throws Exception {
        String serverName;
        try {
            serverName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            serverName = "unknown";
        }

        // Read Sati SDK version from JAR manifest, fall back to "dev"
        String satiVersion = JobProcessor.class.getPackage().getImplementationVersion();
        if (satiVersion == null)
            satiVersion = "dev";

        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setInfoResult(
                        SubmitJobResultsRequest.InfoResult.newBuilder()
                                .setServerName(serverName)
                                .setCoreVersion("sati-" + satiVersion)
                                .setPluginName(appName)
                                .setPluginVersion(appVersion)
                                .build())
                .build();

        submitResult(request);
    }

    private void handleDiagnostics(String jobId) throws Exception {
        Runtime rt = Runtime.getRuntime();
        String serverName;
        try {
            serverName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            serverName = "unknown";
        }

        java.time.Instant now = java.time.Instant.now();
        var timestamp = com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();

        // OS info
        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        var osBuilder = SubmitJobResultsRequest.DiagnosticsResult.OperatingSystem.newBuilder()
                .setName(System.getProperty("os.name", "unknown"))
                .setVersion(System.getProperty("os.version", "unknown"))
                .setArchitecture(System.getProperty("os.arch", "unknown"))
                .setManufacturer(System.getProperty("os.name", "unknown"))
                .setAvailableProcessors(rt.availableProcessors())
                .setSystemUptime(uptimeMs)
                .setSystemLoadAverage(osBean.getSystemLoadAverage());

        // Java runtime info
        var runtimeBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
        var javaBuilder = SubmitJobResultsRequest.DiagnosticsResult.JavaRuntime.newBuilder()
                .setVersion(System.getProperty("java.version", "unknown"))
                .setVendor(System.getProperty("java.vendor", "unknown"))
                .setRuntimeName(System.getProperty("java.runtime.name", "unknown"))
                .setVmName(System.getProperty("java.vm.name", "unknown"))
                .setVmVersion(System.getProperty("java.vm.version", "unknown"))
                .setVmVendor(System.getProperty("java.vm.vendor", "unknown"))
                .setSpecificationName(System.getProperty("java.specification.name", "unknown"))
                .setSpecificationVersion(System.getProperty("java.specification.version", "unknown"))
                .setClassPath(System.getProperty("java.class.path", ""))
                .setLibraryPath(System.getProperty("java.library.path", ""))
                .setUptime(uptimeMs)
                .setStartTime(runtimeBean.getStartTime())
                .setManagementSpecVersion(System.getProperty("java.management.spec.version",
                        runtimeBean.getManagementSpecVersion()));

        // Memory info with pools
        var memBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        var heap = memBean.getHeapMemoryUsage();
        var nonHeap = memBean.getNonHeapMemoryUsage();
        SubmitJobResultsRequest.DiagnosticsResult.Memory.Builder memBuilder =
                SubmitJobResultsRequest.DiagnosticsResult.Memory.newBuilder()
                .setHeapMemoryUsed(heap.getUsed())
                .setHeapMemoryMax(heap.getMax())
                .setHeapMemoryCommitted(heap.getCommitted())
                .setNonHeapMemoryUsed(nonHeap.getUsed())
                .setNonHeapMemoryMax(nonHeap.getMax())
                .setNonHeapMemoryCommitted(nonHeap.getCommitted());
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            var usage = pool.getUsage();
            if (usage != null) {
                memBuilder.addMemoryPools(
                        SubmitJobResultsRequest.DiagnosticsResult.MemoryPool.newBuilder()
                                .setName(pool.getName())
                                .setType(pool.getType().toString())
                                .setUsed(usage.getUsed())
                                .setMax(usage.getMax())
                                .setCommitted(usage.getCommitted())
                                .build());
            }
        }

        // Hardware
        var hwBuilder = SubmitJobResultsRequest.DiagnosticsResult.Hardware.newBuilder()
                .setModel("Unknown").setManufacturer("Unknown")
                .setSerialNumber("Unknown").setUuid("Unknown")
                .setProcessor(SubmitJobResultsRequest.DiagnosticsResult.Processor.newBuilder()
                        .setName("Unknown")
                        .setIdentifier(System.getProperty("java.vm.name", "unknown"))
                        .setArchitecture(System.getProperty("os.arch", "unknown"))
                        .setPhysicalProcessorCount(rt.availableProcessors())
                        .setLogicalProcessorCount(rt.availableProcessors())
                        .setMaxFrequency(-1)
                        .setCpu64Bit(System.getProperty("os.arch", "").contains("64") ||
                                System.getProperty("os.arch", "").equals("aarch64"))
                        .build());

        // Storage
        var rootFile = new java.io.File("/");
        var storageBuilder = SubmitJobResultsRequest.DiagnosticsResult.Storage.newBuilder()
                .setName("/").setType("disk").setModel("Unknown")
                .setSerialNumber("Unknown").setSize(rootFile.getTotalSpace());

        // Container detection
        var containerBuilder = SubmitJobResultsRequest.DiagnosticsResult.Container.newBuilder();
        boolean inDocker = new java.io.File("/.dockerenv").exists();
        if (inDocker) {
            containerBuilder.setIsContainer(true).setContainerType("docker");
            containerBuilder.setContainerId(serverName).setContainerName(serverName);
            containerBuilder.setImageName("unknown");
        } else {
            containerBuilder.setContainerType("unknown").setImageName("unknown");
        }

        // Environment variables
        var envBuilder = SubmitJobResultsRequest.DiagnosticsResult.EnvironmentVariables.newBuilder();
        var env = System.getenv();
        if (env.containsKey("PATH")) envBuilder.setPath(env.get("PATH"));
        if (env.containsKey("JAVA_HOME")) envBuilder.setJavaHome(env.get("JAVA_HOME"));
        if (env.containsKey("LANG")) envBuilder.setLang(env.get("LANG"));
        if (env.containsKey("HOME")) envBuilder.setHome(env.get("HOME"));
        if (env.containsKey("HOSTNAME")) envBuilder.setHostname(env.get("HOSTNAME"));

        // System properties
        var propsBuilder = SubmitJobResultsRequest.DiagnosticsResult.SystemProperties.newBuilder()
                .setJavaSpecificationVersion(System.getProperty("java.specification.version", ""))
                .setJavaSpecificationVendor(System.getProperty("java.specification.vendor", ""))
                .setJavaSpecificationName(System.getProperty("java.specification.name", ""))
                .setJavaVersion(System.getProperty("java.version", ""))
                .setJavaVersionDate(System.getProperty("java.version.date", ""))
                .setJavaVendor(System.getProperty("java.vendor", ""))
                .setJavaVendorVersion(System.getProperty("java.vendor.version", ""))
                .setJavaVendorUrl(System.getProperty("java.vendor.url", ""))
                .setJavaVendorUrlBug(System.getProperty("java.vendor.url.bug", ""))
                .setJavaRuntimeName(System.getProperty("java.runtime.name", ""))
                .setJavaRuntimeVersion(System.getProperty("java.runtime.version", ""))
                .setJavaHome(System.getProperty("java.home", ""))
                .setJavaClassPath(System.getProperty("java.class.path", ""))
                .setJavaLibraryPath(System.getProperty("java.library.path", ""))
                .setJavaClassVersion(System.getProperty("java.class.version", ""))
                .setJavaVmName(System.getProperty("java.vm.name", ""))
                .setJavaVmVersion(System.getProperty("java.vm.version", ""))
                .setJavaVmVendor(System.getProperty("java.vm.vendor", ""))
                .setJavaVmInfo(System.getProperty("java.vm.info", ""))
                .setJavaVmSpecificationVersion(System.getProperty("java.vm.specification.version", ""))
                .setJavaVmSpecificationVendor(System.getProperty("java.vm.specification.vendor", ""))
                .setJavaVmSpecificationName(System.getProperty("java.vm.specification.name", ""))
                .setOsName(System.getProperty("os.name", ""))
                .setOsVersion(System.getProperty("os.version", ""))
                .setOsArch(System.getProperty("os.arch", ""))
                .setUserName(System.getProperty("user.name", ""))
                .setUserHome(System.getProperty("user.home", ""))
                .setUserDir(System.getProperty("user.dir", ""))
                .setUserTimezone(System.getProperty("user.timezone", ""))
                .setUserCountry(System.getProperty("user.country", ""))
                .setUserLanguage(System.getProperty("user.language", ""))
                .setFileSeparator(System.getProperty("file.separator", ""))
                .setPathSeparator(System.getProperty("path.separator", ""))
                .setLineSeparator(System.getProperty("line.separator", ""))
                .setFileEncoding(System.getProperty("file.encoding", ""))
                .setSunArchDataModel(System.getProperty("sun.arch.data.model", ""))
                .setSunJavaLauncher(System.getProperty("sun.java.launcher", ""))
                .setSunJavaCommand(System.getProperty("sun.java.command", ""))
                .setSunCpuEndian(System.getProperty("sun.cpu.endian", ""))
                .setJavaIoTmpdir(System.getProperty("java.io.tmpdir", ""));

        // Config details from Gate
        var configBuilder = SubmitJobResultsRequest.DiagnosticsResult.ConfigDetails.newBuilder();
        try {
            String apiEndpoint = "https://" + gateClient.getConfig().apiHostname();
            configBuilder.setApiEndpoint(apiEndpoint);
            String configName = gateClient.getConfigName();
            if (configName != null) configBuilder.setCertificateName(configName);
            String certExp = gateClient.getCertExpiration();
            if (certExp != null) configBuilder.setCertificateDescription(certExp);
        } catch (Exception e) {
            log.debug("Could not populate config details: {}", e.getMessage());
        }

        // Event stream stats
        var eventStats = SubmitJobResultsRequest.DiagnosticsResult.EventStreamStats.newBuilder()
                .setStreamName("application")
                .setStatus("running")
                .setMaxJobs(executor.getMaximumPoolSize())
                .setRunningJobs(executor.getActiveCount())
                .setCompletedJobs(processedJobs.get())
                .setQueuedJobs(jobQueue.size());

        SubmitJobResultsRequest.DiagnosticsResult.Builder diagnosticsBuilder =
                SubmitJobResultsRequest.DiagnosticsResult.newBuilder()
                .setTimestamp(timestamp)
                .setHostname(serverName)
                .setOperatingSystem(osBuilder.build())
                .setJavaRuntime(javaBuilder.build())
                .setMemory(memBuilder.build())
                .setHardware(hwBuilder.build())
                .addStorage(storageBuilder.build())
                .setContainer(containerBuilder.build())
                .setEnvironmentVariables(envBuilder.build())
                .setSystemProperties(propsBuilder.build())
                .setConfigDetails(configBuilder.build())
                .setEventStreamStats(eventStats.build());

        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setDiagnosticsResult(diagnosticsBuilder.build())
                .build();

        submitResult(request);
    }

    private void handleShutdown(String jobId) {
        log.warn("Received shutdown request, initiating graceful shutdown...");

        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setShutdownResult(
                        SubmitJobResultsRequest.SeppukuResult.newBuilder()
                                .build())
                .build();

        submitResult(request);

        // Trigger shutdown in background
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-thread").start();
    }

    private void handlePopAccount(String jobId, StreamJobsResponse.PopAccountRequest pop) throws Exception {
        // Extract callerId and phoneNumber from filters
        String callerId = null;
        String phoneNumber = null;
        for (var filter : pop.getFiltersList()) {
            if (filter.getKey().equalsIgnoreCase("callerId"))
                callerId = filter.getValue();
            if (filter.getKey().equalsIgnoreCase("phoneNumber"))
                phoneNumber = filter.getValue();
        }

        var request = new PopAccountRequest(
                pop.getRecordId(), pop.getPartnerAgentId(), pop.getCallSid(),
                pop.getCallType().name(), callerId, phoneNumber);
        backendClient.popAccount(request);

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setPopAccountResult(SubmitJobResultsRequest.PopAccountResult.newBuilder().build())
                .build());
    }

    private void handleSearchRecords(String jobId, StreamJobsResponse.SearchRecordsRequest search) throws Exception {
        Map<String, String> filters = new HashMap<>();
        for (var filter : search.getFiltersList()) {
            filters.put(filter.getKey(), filter.getValue());
        }

        var request = new SearchRecordsRequest(search.getLookupType(), search.getLookupValue(), filters);
        var results = backendClient.searchRecords(request);

        for (var result : results) {
            submitResult(SubmitJobResultsRequest.newBuilder()
                    .setJobId(jobId)
                    .setEndOfTransmission(false)
                    .setSearchRecordResult(SubmitJobResultsRequest.SearchRecordResult.newBuilder()
                            .addRecords(Record.newBuilder()
                                    .setRecordId(result.recordId)
                                    .setPoolId(result.poolId)
                                    .build())
                            .build())
                    .build());
        }

        // End transmission
        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .build());
    }

    private void handleReadFields(String jobId, StreamJobsResponse.GetRecordFieldsRequest readReq) throws Exception {
        Map<String, String> filters = new HashMap<>();
        for (var filter : readReq.getFiltersList()) {
            filters.put(filter.getKey(), filter.getValue());
        }

        var request = new ReadFieldsRequest(
                readReq.getRecordId(), readReq.getPoolId(),
                readReq.getFieldNamesList(), filters);
        var fields = backendClient.readFields(request);

        var resultBuilder = SubmitJobResultsRequest.GetRecordFieldsResult.newBuilder();
        for (var field : fields) {
            resultBuilder.addFields(Field.newBuilder()
                    .setRecordId(field.recordId)
                    .setPoolId(field.poolId)
                    .setFieldName(field.fieldName)
                    .setFieldValue(field.fieldValue)
                    .build());
        }

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setGetRecordFieldsResult(resultBuilder.build())
                .build());
    }

    private void handleWriteFields(String jobId, StreamJobsResponse.SetRecordFieldsRequest writeReq) throws Exception {
        Map<String, String> fieldMap = new HashMap<>();
        for (var field : writeReq.getFieldsList()) {
            fieldMap.put(field.getFieldName(), field.getFieldValue());
        }
        Map<String, String> filters = new HashMap<>();
        for (var filter : writeReq.getFiltersList()) {
            filters.put(filter.getKey(), filter.getValue());
        }

        var request = new WriteFieldsRequest(writeReq.getRecordId(), fieldMap, filters);
        backendClient.writeFields(request);

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setSetRecordFieldsResult(SubmitJobResultsRequest.SetRecordFieldsResult.newBuilder().build())
                .build());
    }

    private void handleCreatePayment(String jobId, StreamJobsResponse.CreatePaymentRequest payReq) throws Exception {
        long epochSeconds = payReq.hasPaymentDate()
                ? payReq.getPaymentDate().getSeconds()
                : 0;

        var request = new CreatePaymentRequest(
                payReq.getRecordId(), payReq.getPaymentId(),
                payReq.getPaymentType(), payReq.getPaymentAmount(), epochSeconds);
        backendClient.createPayment(request);

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setCreatePaymentResult(SubmitJobResultsRequest.CreatePaymentResult.newBuilder().build())
                .build());
    }

    private void handleExecuteLogic(String jobId, StreamJobsResponse.ExecuteLogicRequest execReq) throws Exception {
        var request = new ExecuteLogicRequest(execReq.getLogicBlockId(), execReq.getLogicBlockParams());
        String result = backendClient.executeLogic(request);

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setExecuteLogicResult(SubmitJobResultsRequest.ExecuteLogicResult.newBuilder()
                        .setResult(result)
                        .build())
                .build());
    }

    // ========== Tenant Log/Level Handlers ==========

    private void handleListTenantLogs(String jobId, StreamJobsResponse.ListTenantLogsRequest request) {
        // Extract time range from request and filter logs accordingly
        List<String> logs;
        if (request.hasTimeRange()) {
            long startTimeMs = request.getTimeRange().getStartTime().getSeconds() * 1000
                    + request.getTimeRange().getStartTime().getNanos() / 1000000;
            long endTimeMs = request.getTimeRange().getEndTime().getSeconds() * 1000
                    + request.getTimeRange().getEndTime().getNanos() / 1000000;
            log.debug("Filtering logs with time range: {} to {} (timestamps: {} to {})",
                    request.getTimeRange().getStartTime(), request.getTimeRange().getEndTime(),
                    startTimeMs, endTimeMs);
            logs = MemoryLogAppender.getLogsInTimeRange(startTimeMs, endTimeMs);
            log.debug("Retrieved {} logs within time range {} to {}", logs.size(), startTimeMs, endTimeMs);
        } else {
            logs = MemoryLogAppender.getRecentLogs(200);
        }

        var resultBuilder = SubmitJobResultsRequest.ListTenantLogsResult.newBuilder();

        if (!logs.isEmpty()) {
            var logGroupBuilder = SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.newBuilder()
                    .setName("logGroups/memory-logs")
                    .addAllLogs(logs);

            // Echo back the request's time range, or fall back to current time
            if (request.hasTimeRange()) {
                logGroupBuilder.setTimeRange(request.getTimeRange());
            } else {
                long now = System.currentTimeMillis();
                logGroupBuilder.setTimeRange(build.buf.gen.tcnapi.exile.gate.v2.TimeRange.newBuilder()
                        .setStartTime(com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(now / 1000).setNanos((int) ((now % 1000) * 1000000)).build())
                        .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(now / 1000).setNanos((int) ((now % 1000) * 1000000)).build())
                        .build());
            }

            // Detect actual log levels from logback
            detectLogLevels().forEach(logGroupBuilder::putLogLevels);

            resultBuilder.addLogGroups(logGroupBuilder.build());
        }

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setListTenantLogsResult(resultBuilder.build())
                .build());
    }

    private Map<String, SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel> detectLogLevels() {
        Map<String, SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel> result = new HashMap<>();
        try {
            var loggerContext = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            for (var logger : loggerContext.getLoggerList()) {
                var level = logger.getLevel();
                if (level != null) {
                    // Logger has an explicitly set level
                    result.put(logger.getName(), toProtoLogLevel(level));
                } else {
                    // Logger inherits its level from parent — include with (inherited) suffix
                    var effectiveLevel = logger.getEffectiveLevel();
                    if (effectiveLevel != null) {
                        result.put(logger.getName() + " (inherited)", toProtoLogLevel(effectiveLevel));
                    }
                }
            }
            var rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            var rootLevel = rootLogger.getLevel();
            if (rootLevel != null) {
                result.put("ROOT", toProtoLogLevel(rootLevel));
            }
        } catch (Exception e) {
            log.warn("Could not detect log levels: {}", e.getMessage());
        }
        if (result.isEmpty()) {
            result.put("memory", SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel.INFO);
        }
        return result;
    }

    private SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel toProtoLogLevel(
            ch.qos.logback.classic.Level level) {
        return switch (level.toInt()) {
            case ch.qos.logback.classic.Level.DEBUG_INT ->
                    SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel.DEBUG;
            case ch.qos.logback.classic.Level.WARN_INT ->
                    SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel.WARNING;
            case ch.qos.logback.classic.Level.ERROR_INT ->
                    SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel.ERROR;
            default -> SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.LogLevel.INFO;
        };
    }

    private void handleSetLogLevel(String jobId, StreamJobsResponse.SetLogLevelRequest req) {
        String level = req.getLogLevel().name();
        log.info("Setting log level to: {}", level);

        try {
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO));
            log.info("Log level set to: {}", rootLogger.getLevel());
        } catch (Exception e) {
            log.warn("Failed to set log level: {}", e.getMessage());
        }

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setSetLogLevelResult(SubmitJobResultsRequest.SetLogLevelResult.newBuilder().build())
                .build());
    }

    private void handleLogging(String jobId, StreamJobsResponse.LoggingRequest req) {
        // Legacy logging job — applies per-logger level settings
        for (var loggerLevel : req.getLoggerLevelsList()) {
            String loggerName = loggerLevel.getLoggerName();
            String level = loggerLevel.getLoggerLevel().name();
            log.info("Logging request: setting {} to level {}", loggerName, level);

            try {
                ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory
                        .getLogger(loggerName.isEmpty() ? Logger.ROOT_LOGGER_NAME : loggerName);
                logger.setLevel(ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO));
            } catch (Exception e) {
                log.warn("Failed to set log level for {}: {}", loggerName, e.getMessage());
            }
        }

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setLoggingResult(SubmitJobResultsRequest.LoggingResult.newBuilder().build())
                .build());
    }

    // ========== Helpers ==========

    private boolean isAdminJob(StreamJobsResponse job) {
        return job.hasInfo() || job.hasDiagnostics() || job.hasShutdown()
                || job.hasLogging() || job.hasListTenantLogs() || job.hasSetLogLevel();
    }

    private void submitResult(SubmitJobResultsRequest request) {
        try {
            log.info("Submitting job result for job {} (resultCase: {}, size: {} bytes)",
                    request.getJobId(), request.getResultCase(), request.getSerializedSize());
            var response = GateServiceGrpc.newBlockingStub(gateClient.getChannel())
                    .withDeadlineAfter(300, TimeUnit.SECONDS)
                    .withWaitForReady()
                    .submitJobResults(request);
            log.info("Successfully submitted job result for job {}. Response: {}",
                    request.getJobId(), response);
        } catch (Exception e) {
            log.error("Failed to submit result for job {}: {}", request.getJobId(), e.getMessage(), e);
        }
    }

    private void submitError(String jobId, String errorMessage) {
        try {
            var request = SubmitJobResultsRequest.newBuilder()
                    .setJobId(jobId)
                    .setEndOfTransmission(true)
                    .setErrorResult(SubmitJobResultsRequest.ErrorResult.newBuilder()
                            .setMessage(errorMessage)
                            .build())
                    .build();

            submitResult(request);

        } catch (Exception e) {
            log.error("Failed to submit error for job {}: {}", jobId, e.getMessage());
        }
    }

    public void setMaxWorkers(int maxWorkers) {
        log.info("Setting max workers to {}", maxWorkers);
        executor.setMaximumPoolSize(maxWorkers);
        executor.setCorePoolSize(Math.min(2, maxWorkers));
    }

    public int getActiveWorkers() {
        return executor.getActiveCount();
    }

    public int getQueueSize() {
        return jobQueue.size();
    }

    public long getProcessedJobs() {
        return processedJobs.get();
    }

    public long getFailedJobs() {
        return failedJobs.get();
    }

    @Override
    public void close() {
        log.info("Shutting down job processor...");
        shuttingDown = true;
        executor.shutdown();

        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Job processor shutdown complete (processed: {}, failed: {})",
                processedJobs.get(), failedJobs.get());
    }
}
