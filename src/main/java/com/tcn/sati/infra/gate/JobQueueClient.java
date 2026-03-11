package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v2.*;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Bidirectional job streaming with acknowledgment using JobQueueStream API.
 * 
 * This replaces the deprecated StreamJobs API with a more reliable pattern
 * where jobs are explicitly acknowledged. If a client disconnects without
 * ACKing, jobs are redelivered to another client.
 * 
 * Flow:
 * 1. Open bidirectional stream
 * 2. Send initial empty request to start receiving
 * 3. Receive job, process it, send ACK with job_id
 * 4. Receive next job...
 */
public class JobQueueClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobQueueClient.class);
    private static final String KEEPALIVE_JOB_ID = "keepalive";

    private static final long RECONNECT_BASE_MS = 5_000;
    private static final long RECONNECT_MAX_MS = 60_000;
    private static final double BACKOFF_JITTER = 0.2;
    private static final long HUNG_THRESHOLD_SECONDS = 45;
    private static final long STREAM_TIMEOUT_MINUTES = 5;

    private final GateClient gateClient;
    private final Function<StreamJobsResponse, Boolean> jobHandler;
    private final Thread streamThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong jobsProcessed = new AtomicLong(0);
    private final AtomicLong jobsFailed = new AtomicLong(0);
    private final AtomicLong reconnectAttempts = new AtomicLong(0);

    /**
     * @param gateClient Gate gRPC client
     * @param jobHandler Function that processes a job and returns true if
     *                   successful (should ACK)
     */
    public JobQueueClient(GateClient gateClient, Function<StreamJobsResponse, Boolean> jobHandler) {
        this.gateClient = gateClient;
        this.jobHandler = jobHandler;
        this.streamThread = new Thread(this::streamLoop, "job-queue");
        this.streamThread.setDaemon(true);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting job queue client");
            streamThread.start();
        }
    }

    private void streamLoop() {
        long consecutiveFailures = 0;

        while (running.get()) {
            try {
                reconnectAttempts.incrementAndGet();

                // Backoff with jitter before reconnect
                long backoffMs = computeBackoff(consecutiveFailures);
                if (backoffMs > 0) {
                    log.debug("Waiting {}ms before reconnect (failures: {})",
                            backoffMs, consecutiveFailures);
                    sleep(backoffMs);
                }

                log.info("Connecting to job queue (attempt #{})...", reconnectAttempts.get());
                gateClient.resetConnectBackoff();
                runStream();

                // Normal completion — reset
                consecutiveFailures = 0;

            } catch (InterruptedException e) {
                log.info("Job queue interrupted");
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                connected.set(false);
                consecutiveFailures++;
                log.warn("Job queue error (failure #{}): {}",
                        consecutiveFailures, e.getMessage());
            }
        }

        connected.set(false);
        log.info("Job queue stopped (processed: {}, failed: {})",
                jobsProcessed.get(), jobsFailed.get());
    }

    private void runStream() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var requestObserver = new AtomicReference<StreamObserver<JobQueueStreamRequest>>();
        var errorRef = new AtomicReference<Throwable>();
        var lastMessageTime = new AtomicReference<>(System.currentTimeMillis());

        // Create response handler
        var responseObserver = new StreamObserver<JobQueueStreamResponse>() {
            @Override
            public void onNext(JobQueueStreamResponse response) {
                lastMessageTime.set(System.currentTimeMillis());

                // Log connected on first server response
                if (connected.compareAndSet(false, true)) {
                    log.info("✅ Connected to job queue (received server response)");
                }

                if (!response.hasJob()) {
                    log.debug("Received heartbeat from server");
                    return;
                }

                var job = response.getJob();
                String jobId = job.getJobId();
                log.debug("Received job: {} (type: {})", jobId, getJobType(job));

                // Handle keepalive — just ACK to register with presence store
                if (KEEPALIVE_JOB_ID.equals(jobId)) {
                    log.debug("Received keepalive, sending ACK to register");
                    sendAck(requestObserver.get(), KEEPALIVE_JOB_ID);
                    return;
                }

                // Process the job
                boolean success = false;
                try {
                    success = jobHandler.apply(job);
                    if (success) {
                        jobsProcessed.incrementAndGet();
                    } else {
                        jobsFailed.incrementAndGet();
                    }
                } catch (Exception e) {
                    jobsFailed.incrementAndGet();
                    log.error("Job handler threw exception for {}: {}", jobId, e.getMessage());
                }

                // Send ACK only on success
                if (success) {
                    sendAck(requestObserver.get(), jobId);
                }
                // If not success, don't ACK - job will be redelivered to another client
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Job queue stream error: {}", t.getMessage());
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("Job queue stream completed by server");
                latch.countDown();
            }
        };

        // Open bidirectional stream
        var observer = GateServiceGrpc.newStub(gateClient.getChannel())
                .jobQueueStream(responseObserver);
        requestObserver.set(observer);

        // Send initial keepalive to register with server
        // (Server expects "keepalive" as JobId for heartbeat messages)
        log.debug("Sending initial keepalive to job queue...");
        observer.onNext(JobQueueStreamRequest.newBuilder()
                .setJobId(KEEPALIVE_JOB_ID)
                .build());

        // Wait for stream to complete, checking for hung connections
        awaitStreamWithHungDetection(latch, lastMessageTime);
        connected.set(false);

        // Propagate error if any
        var error = errorRef.get();
        if (error instanceof StatusRuntimeException sre) {
            throw sre;
        } else if (error != null) {
            throw new RuntimeException("Stream error", error);
        }
    }

    private void sendAck(StreamObserver<JobQueueStreamRequest> observer, String jobId) {
        if (observer == null) {
            log.warn("Cannot send ACK for job {} - no active observer", jobId);
            return;
        }
        try {
            observer.onNext(JobQueueStreamRequest.newBuilder()
                    .setJobId(jobId)
                    .build());
            log.debug("Sent ACK for job: {}", jobId);
        } catch (Exception e) {
            log.error("Failed to send ACK for job {}: {}", jobId, e.getMessage());
        }
    }

    private String getJobType(StreamJobsResponse job) {
        if (job.hasListPools())
            return "listPools";
        if (job.hasGetPoolStatus())
            return "getPoolStatus";
        if (job.hasGetPoolRecords())
            return "getPoolRecords";
        if (job.hasSearchRecords())
            return "searchRecords";
        if (job.hasGetRecordFields())
            return "getRecordFields";
        if (job.hasSetRecordFields())
            return "setRecordFields";
        if (job.hasCreatePayment())
            return "createPayment";
        if (job.hasPopAccount())
            return "popAccount";
        if (job.hasInfo())
            return "info";
        if (job.hasShutdown())
            return "shutdown";
        if (job.hasLogging())
            return "logging";
        if (job.hasExecuteLogic())
            return "executeLogic";
        if (job.hasDiagnostics())
            return "diagnostics";
        if (job.hasListTenantLogs())
            return "listTenantLogs";
        if (job.hasSetLogLevel())
            return "setLogLevel";
        return "unknown";
    }

    /**
     * Wait for stream latch, checking for hung connections periodically.
     */
    private void awaitStreamWithHungDetection(CountDownLatch latch,
            AtomicReference<Long> lastMessageTime) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long maxDurationMs = TimeUnit.MINUTES.toMillis(STREAM_TIMEOUT_MINUTES);

        while ((System.currentTimeMillis() - startTime) < maxDurationMs) {
            if (latch.await(HUNG_THRESHOLD_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            long lastMsg = lastMessageTime.get();
            long silenceMs = System.currentTimeMillis() - lastMsg;
            if (silenceMs > TimeUnit.SECONDS.toMillis(HUNG_THRESHOLD_SECONDS)) {
                throw new RuntimeException(
                        "Job queue stream appears hung - no messages for " + (silenceMs / 1000) + "s");
            }
        }
    }

    /**
     * Compute backoff with jitter. Prevents thundering herd.
     */
    private long computeBackoff(long failures) {
        if (failures <= 0) return 0;
        long delayMs = RECONNECT_BASE_MS * (1L << Math.min(failures - 1, 10));
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
        return Math.min((long) (delayMs * jitter), RECONNECT_MAX_MS);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public long getJobsProcessed() {
        return jobsProcessed.get();
    }

    public long getJobsFailed() {
        return jobsFailed.get();
    }

    @Override
    public void close() {
        log.info("Shutting down job queue client...");
        running.set(false);
        streamThread.interrupt();

        try {
            streamThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
