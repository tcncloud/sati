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
                handleListTenantLogs(jobId);

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
        var records = backendClient.getPoolRecords(poolId, 0);

        var resultBuilder = SubmitJobResultsRequest.GetPoolRecordsResult.newBuilder();

        for (var rec : records) {
            resultBuilder.addRecords(
                    Record.newBuilder()
                            .setRecordId(rec.recordId)
                            .build());
        }

        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setGetPoolRecordsResult(resultBuilder)
                .build();

        submitResult(request);
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

        var diagnosticsBuilder = SubmitJobResultsRequest.DiagnosticsResult.newBuilder()
                .setHostname(serverName)
                .setOperatingSystem(
                        SubmitJobResultsRequest.DiagnosticsResult.OperatingSystem.newBuilder()
                                .setName(System.getProperty("os.name", "unknown"))
                                .setVersion(System.getProperty("os.version", "unknown"))
                                .setArchitecture(System.getProperty("os.arch", "unknown"))
                                .setAvailableProcessors(rt.availableProcessors())
                                .build())
                .setJavaRuntime(
                        SubmitJobResultsRequest.DiagnosticsResult.JavaRuntime.newBuilder()
                                .setVersion(System.getProperty("java.version", "unknown"))
                                .setVendor(System.getProperty("java.vendor", "unknown"))
                                .build())
                .setMemory(
                        SubmitJobResultsRequest.DiagnosticsResult.Memory.newBuilder()
                                .setHeapMemoryUsed(rt.totalMemory() - rt.freeMemory())
                                .setHeapMemoryMax(rt.maxMemory())
                                .setHeapMemoryCommitted(rt.totalMemory())
                                .build());

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
                "inbound", callerId, phoneNumber);
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

    private void handleListTenantLogs(String jobId) {
        List<String> logs = MemoryLogAppender.getRecentLogs(200);

        var logGroup = SubmitJobResultsRequest.ListTenantLogsResult.LogGroup.newBuilder()
                .setName("application")
                .addAllLogs(logs)
                .build();

        submitResult(SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setListTenantLogsResult(SubmitJobResultsRequest.ListTenantLogsResult.newBuilder()
                        .addLogGroups(logGroup)
                        .build())
                .build());
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
            GateServiceGrpc.newBlockingStub(gateClient.getChannel())
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .submitJobResults(request);

        } catch (Exception e) {
            log.error("Failed to submit result for job {}: {}", request.getJobId(), e.getMessage());
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
