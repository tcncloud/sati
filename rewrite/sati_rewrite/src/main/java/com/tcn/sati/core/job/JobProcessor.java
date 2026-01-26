package com.tcn.sati.core.job;

import build.buf.gen.tcnapi.exile.core.v2.Pool;
import build.buf.gen.tcnapi.exile.core.v2.Record;
import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.gate.GateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
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
    
    private final AtomicLong processedJobs = new AtomicLong(0);
    private final AtomicLong failedJobs = new AtomicLong(0);
    
    private volatile boolean shuttingDown = false;

    public JobProcessor(TenantBackendClient backendClient, GateClient gateClient) {
        this(backendClient, gateClient, 5); // Default 5 worker threads
    }

    public JobProcessor(TenantBackendClient backendClient, GateClient gateClient, int maxWorkers) {
        this.backendClient = backendClient;
        this.gateClient = gateClient;
        this.jobQueue = new LinkedBlockingQueue<>(1000); // Bounded queue
        this.executor = new ThreadPoolExecutor(
            2,              // core threads
            maxWorkers,     // max threads
            60L,            // idle timeout
            TimeUnit.SECONDS,
            jobQueue,
            r -> {
                Thread t = new Thread(r, "job-worker");
                t.setDaemon(true);
                return t;
            }
        );
        
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
                    .setPoolId(pool.id())
                    .setDescription(pool.name())
                    .build()
            );
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
                            .setRecordCount(status.totalRecords())
                            .build())
                        .build()
                )
                .build();
        
        submitResult(request);
    }

    private void handleGetPoolRecords(String jobId, String poolId) throws Exception {
        var records = backendClient.getPoolRecords(poolId, 0);
        
        var resultBuilder = SubmitJobResultsRequest.GetPoolRecordsResult.newBuilder();
        
        for (var rec : records) {
            resultBuilder.addRecords(
                Record.newBuilder()
                    .setRecordId(rec.recordId())
                    .build()
            );
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
        
        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setInfoResult(
                    SubmitJobResultsRequest.InfoResult.newBuilder()
                        .setServerName(serverName)
                        .setCoreVersion("sati-rewrite-1.0")
                        .setPluginName("Sati Rewrite")
                        .setPluginVersion("1.0.0")
                        .build()
                )
                .build();
        
        submitResult(request);
    }

    private void handleDiagnostics(String jobId) throws Exception {
        // DiagnosticsResult is built by a separate DiagnosticsService in the old code
        // For now, just return an empty result to acknowledge
        var request = SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setDiagnosticsResult(
                    SubmitJobResultsRequest.DiagnosticsResult.newBuilder()
                        .build()
                )
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
                        .build()
                )
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
