package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsRequest;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streams jobs from Gate and dispatches them to a handler.
 * 
 * Features:
 * - Persistent gRPC stream connection
 * - Auto-reconnect on disconnect with exponential backoff
 * - Hung connection detection (no messages for 45s)
 * - Graceful shutdown
 */
public class JobStreamClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobStreamClient.class);
    
    private static final long RECONNECT_DELAY_MS = 5_000;       // 5 seconds
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;  // 60 seconds max
    private static final long HUNG_TIMEOUT_MS = 45_000;         // 45 seconds
    
    private final GateClient gateClient;
    private final Consumer<StreamJobsResponse> jobHandler;
    private final Thread streamThread;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong reconnectAttempts = new AtomicLong(0);
    private final AtomicLong successfulConnections = new AtomicLong(0);
    private final AtomicLong jobsReceived = new AtomicLong(0);
    private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>();
    private final AtomicReference<Instant> connectionTime = new AtomicReference<>();
    
    public JobStreamClient(GateClient gateClient, Consumer<StreamJobsResponse> jobHandler) {
        this.gateClient = gateClient;
        this.jobHandler = jobHandler;
        this.streamThread = new Thread(this::streamLoop, "job-stream");
        this.streamThread.setDaemon(true);
    }

    /**
     * Start streaming jobs from Gate.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting job stream client...");
            streamThread.start();
        }
    }

    /**
     * Main stream loop - runs until shutdown.
     */
    private void streamLoop() {
        long currentDelay = RECONNECT_DELAY_MS;
        
        while (running.get()) {
            try {
                reconnectAttempts.incrementAndGet();
                log.info("Connecting to Gate job stream (attempt #{})...", reconnectAttempts.get());
                
                streamJobs();
                
                // If we get here, stream completed normally - reset delay
                currentDelay = RECONNECT_DELAY_MS;
                
            } catch (StatusRuntimeException e) {
                connected.set(false);
                
                if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                    log.warn("Gate unavailable, will retry in {}s", currentDelay / 1000);
                } else if (e.getStatus().getCode() == Status.Code.CANCELLED) {
                    log.info("Stream cancelled (shutdown?)");
                    break;
                } else {
                    log.error("Stream error: {}", e.getStatus());
                }
                
                sleepBeforeRetry(currentDelay);
                currentDelay = Math.min(currentDelay * 2, MAX_RECONNECT_DELAY_MS);
                
            } catch (HungConnectionException e) {
                connected.set(false);
                log.warn("Connection appears hung (no messages for {}s), reconnecting...", HUNG_TIMEOUT_MS / 1000);
                sleepBeforeRetry(RECONNECT_DELAY_MS);
                currentDelay = RECONNECT_DELAY_MS;
                
            } catch (InterruptedException e) {
                log.info("Stream thread interrupted, shutting down");
                Thread.currentThread().interrupt();
                break;
                
            } catch (Exception e) {
                connected.set(false);
                log.error("Unexpected stream error, will retry in {}s", currentDelay / 1000, e);
                sleepBeforeRetry(currentDelay);
                currentDelay = Math.min(currentDelay * 2, MAX_RECONNECT_DELAY_MS);
            }
        }
        
        connected.set(false);
        log.info("Job stream loop ended (total attempts: {}, successful: {}, jobs: {})",
            reconnectAttempts.get(), successfulConnections.get(), jobsReceived.get());
    }

    /**
     * Stream jobs from Gate until disconnect or hung connection.
     */
    private void streamJobs() throws HungConnectionException, InterruptedException {
        ManagedChannel channel = gateClient.getChannel();
        
        Iterator<StreamJobsResponse> jobStream = GateServiceGrpc.newBlockingStub(channel)
                .withWaitForReady()
                .streamJobs(StreamJobsRequest.newBuilder().build());
        
        // Connection established
        connectionTime.set(Instant.now());
        lastMessageTime.set(Instant.now());
        connected.set(true);
        successfulConnections.incrementAndGet();
        log.info("✅ Connected to Gate job stream");
        
        // Process jobs until stream ends
        while (jobStream.hasNext()) {
            // Check for hung connection before blocking on next()
            checkForHungConnection();
            
            StreamJobsResponse job = jobStream.next();
            lastMessageTime.set(Instant.now());
            jobsReceived.incrementAndGet();
            
            log.debug("Received job: {} (type: {})", job.getJobId(), getJobType(job));
            
            try {
                jobHandler.accept(job);
            } catch (Exception e) {
                log.error("Error handling job {}: {}", job.getJobId(), e.getMessage(), e);
                // Don't break stream - submit error and continue
                submitJobError(job.getJobId(), "Handler error: " + e.getMessage());
            }
        }
        
        // Stream completed normally (server closed it)
        log.info("Gate closed job stream gracefully");
    }

    private void checkForHungConnection() throws HungConnectionException {
        Instant lastMsg = lastMessageTime.get();
        if (lastMsg != null) {
            long elapsed = Duration.between(lastMsg, Instant.now()).toMillis();
            if (elapsed > HUNG_TIMEOUT_MS) {
                throw new HungConnectionException("No messages for " + elapsed + "ms");
            }
        }
    }

    private String getJobType(StreamJobsResponse job) {
        if (job.hasListPools()) return "listPools";
        if (job.hasGetPoolStatus()) return "getPoolStatus";
        if (job.hasGetPoolRecords()) return "getPoolRecords";
        if (job.hasSearchRecords()) return "searchRecords";
        if (job.hasGetRecordFields()) return "getRecordFields";
        if (job.hasSetRecordFields()) return "setRecordFields";
        if (job.hasCreatePayment()) return "createPayment";
        if (job.hasPopAccount()) return "popAccount";
        if (job.hasInfo()) return "info";
        if (job.hasShutdown()) return "shutdown";
        if (job.hasLogging()) return "logging";
        if (job.hasExecuteLogic()) return "executeLogic";
        if (job.hasDiagnostics()) return "diagnostics";
        if (job.hasListTenantLogs()) return "listTenantLogs";
        if (job.hasSetLogLevel()) return "setLogLevel";
        return "unknown";
    }

    /**
     * Submit an error result for a job.
     */
    public void submitJobError(String jobId, String errorMessage) {
        try {
            var request = SubmitJobResultsRequest.newBuilder()
                    .setJobId(jobId)
                    .setEndOfTransmission(true)
                    .setErrorResult(SubmitJobResultsRequest.ErrorResult.newBuilder()
                            .setMessage(errorMessage)
                            .build())
                    .build();

            GateServiceGrpc.newBlockingStub(gateClient.getChannel())
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .submitJobResults(request);
                    
        } catch (Exception e) {
            log.error("Failed to submit error for job {}: {}", jobId, e.getMessage());
        }
    }

    private void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public long getJobsReceived() {
        return jobsReceived.get();
    }

    public long getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    @Override
    public void close() {
        log.info("Shutting down job stream client...");
        running.set(false);
        streamThread.interrupt();
        
        try {
            streamThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class HungConnectionException extends Exception {
        HungConnectionException(String message) {
            super(message);
        }
    }
}
