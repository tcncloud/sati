package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v2.*;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
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

    private static final long RECONNECT_DELAY_MS = 5_000;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;

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
        long currentDelay = RECONNECT_DELAY_MS;

        while (running.get()) {
            try {
                reconnectAttempts.incrementAndGet();
                log.info("Connecting to job queue (attempt #{})...", reconnectAttempts.get());

                runStream();

                // Normal completion - reset delay
                currentDelay = RECONNECT_DELAY_MS;

            } catch (StatusRuntimeException e) {
                connected.set(false);
                log.warn("Job queue error: {} - retrying in {}s",
                        e.getStatus(), currentDelay / 1000);
                sleep(currentDelay);
                currentDelay = Math.min(currentDelay * 2, MAX_RECONNECT_DELAY_MS);

            } catch (InterruptedException e) {
                log.info("Job queue interrupted");
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                connected.set(false);
                log.error("Unexpected job queue error", e);
                sleep(currentDelay);
                currentDelay = Math.min(currentDelay * 2, MAX_RECONNECT_DELAY_MS);
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

        // Create response handler
        var responseObserver = new StreamObserver<JobQueueStreamResponse>() {
            @Override
            public void onNext(JobQueueStreamResponse response) {
                // Log connected on first server response
                if (connected.compareAndSet(false, true)) {
                    log.info("✅ Connected to job queue (received server response)");
                }

                if (!response.hasJob()) {
                    log.debug("Received heartbeat from server");
                    return;
                }

                var job = response.getJob();
                log.debug("Received job: {} (type: {})", job.getJobId(), getJobType(job));

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
                    log.error("Job handler threw exception for {}: {}", job.getJobId(), e.getMessage());
                }

                // Send ACK (or next request) only on success
                if (success) {
                    var ack = JobQueueStreamRequest.newBuilder()
                            .setJobId(job.getJobId())
                            .build();
                    var observer = requestObserver.get();
                    if (observer != null) {
                        observer.onNext(ack);
                    }
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
                .setJobId("keepalive")
                .build());

        // Wait for stream to complete
        latch.await();
        connected.set(false);

        // Propagate error if any
        var error = errorRef.get();
        if (error instanceof StatusRuntimeException sre) {
            throw sre;
        } else if (error != null) {
            throw new RuntimeException("Stream error", error);
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
