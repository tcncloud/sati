/*
 *  (C) 2017-2026 TCN Inc. All rights reserved.
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
package com.tcn.exile.gateclients.v2;

import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import build.buf.gen.tcnapi.exile.gate.v2.JobQueueStreamRequest;
import build.buf.gen.tcnapi.exile.gate.v2.JobQueueStreamResponse;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest;
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.log.LogCategory;
import com.tcn.exile.log.StructuredLogger;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Bidirectional job streaming with acknowledgment using JobQueueStream API. */
public class GateClientJobQueue extends GateClientAbstract {
  private static final StructuredLogger log = new StructuredLogger(GateClientJobQueue.class);
  private static final int DEFAULT_TIMEOUT_SECONDS = 300;

  private static final long STREAM_TIMEOUT_MINUTES = 5;
  private static final long HUNG_CONNECTION_THRESHOLD_SECONDS = 45;
  private static final String KEEPALIVE_JOB_ID = "keepalive";

  // Backoff configuration
  private static final long BACKOFF_BASE_MS = 2000;
  private static final long BACKOFF_MAX_MS = 30000;
  private static final double BACKOFF_JITTER = 0.2;

  private final PluginInterface plugin;
  private final AtomicBoolean establishedForCurrentAttempt = new AtomicBoolean(false);
  private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>();

  // Connection timing tracking
  private final AtomicReference<Instant> lastDisconnectTime = new AtomicReference<>();
  private final AtomicReference<Instant> reconnectionStartTime = new AtomicReference<>();
  private final AtomicReference<Instant> connectionEstablishedTime = new AtomicReference<>();
  private final AtomicLong totalReconnectionAttempts = new AtomicLong(0);
  private final AtomicLong successfulReconnections = new AtomicLong(0);
  private final AtomicReference<String> lastErrorType = new AtomicReference<>();
  private final AtomicLong consecutiveFailures = new AtomicLong(0);
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicLong jobsProcessed = new AtomicLong(0);
  private final AtomicLong jobsFailed = new AtomicLong(0);

  // Stream observer for sending ACKs
  private final AtomicReference<StreamObserver<JobQueueStreamRequest>> requestObserverRef =
      new AtomicReference<>();

  public GateClientJobQueue(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  /** Check if the connection is hung (no messages received in threshold time). */
  private void checkForHungConnection() throws HungConnectionException {
    Instant lastMsg = lastMessageTime.get();
    if (lastMsg == null) {
      lastMsg = connectionEstablishedTime.get();
    }
    if (lastMsg != null
        && lastMsg.isBefore(
            Instant.now().minus(HUNG_CONNECTION_THRESHOLD_SECONDS, ChronoUnit.SECONDS))) {
      throw new HungConnectionException(
          "No messages received since " + lastMsg + " - connection appears hung");
    }
  }

  /** Compute backoff delay with jitter based on consecutive failure count. */
  private long computeBackoffMs() {
    long failures = consecutiveFailures.get();
    if (failures <= 0) {
      return 0;
    }
    // Exponential: 2s, 4s, 8s, 16s, capped at 30s
    long delayMs = BACKOFF_BASE_MS * (1L << Math.min(failures - 1, 10));
    // Add ±20% jitter to avoid thundering herd between streams
    double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
    return Math.min((long) (delayMs * jitter), BACKOFF_MAX_MS);
  }

  @Override
  public void start() {
    if (isUnconfigured()) {
      log.warn(LogCategory.GRPC, "NOOP", "JobQueue is unconfigured, cannot stream jobs");
      return;
    }

    // Prevent concurrent invocations from the fixed-rate scheduler
    if (!isRunning.compareAndSet(false, true)) {
      return;
    }

    // Exponential backoff: sleep before retrying if we have consecutive failures
    long backoffMs = computeBackoffMs();
    if (backoffMs > 0) {
      log.debug(
          LogCategory.GRPC,
          "Backoff",
          "Waiting %dms before reconnect attempt (consecutive failures: %d)",
          backoffMs,
          consecutiveFailures.get());
      try {
        Thread.sleep(backoffMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        isRunning.set(false);
        return;
      }
    }

    try {
      log.debug(LogCategory.GRPC, "Init", "JobQueue task started, checking configuration status");

      reconnectionStartTime.set(Instant.now());

      // Reset gRPC's internal DNS backoff so name resolution is retried immediately
      resetChannelBackoff();

      runStream();

    } catch (HungConnectionException e) {
      log.warn(
          LogCategory.GRPC, "HungConnection", "Job queue stream appears hung: %s", e.getMessage());
      lastErrorType.set("HungConnection");
      consecutiveFailures.incrementAndGet();
    } catch (UnconfiguredException e) {
      log.error(LogCategory.GRPC, "ConfigError", "Configuration error: %s", e.getMessage());
      lastErrorType.set("UnconfiguredException");
      consecutiveFailures.incrementAndGet();
    } catch (InterruptedException e) {
      log.info(LogCategory.GRPC, "Interrupted", "Job queue stream interrupted");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC, "JobQueue", "Error streaming jobs from server: %s", e.getMessage(), e);
      lastErrorType.set(e.getClass().getSimpleName());
      consecutiveFailures.incrementAndGet();
    } finally {
      totalReconnectionAttempts.incrementAndGet();
      lastDisconnectTime.set(Instant.now());
      isRunning.set(false);
      requestObserverRef.set(null);
      if (connectionEstablishedTime.get() != null) {
        log.debug(LogCategory.GRPC, "Complete", "Job queue done");
      }
    }
  }

  /** Run a single stream session with bidirectional communication. */
  private void runStream()
      throws UnconfiguredException, InterruptedException, HungConnectionException {
    var latch = new CountDownLatch(1);
    var errorRef = new AtomicReference<Throwable>();
    var firstResponseReceived = new AtomicBoolean(false);

    // Create response handler
    var responseObserver =
        new StreamObserver<JobQueueStreamResponse>() {
          @Override
          public void onNext(JobQueueStreamResponse response) {
            lastMessageTime.set(Instant.now());

            // Log connected on first server response
            if (firstResponseReceived.compareAndSet(false, true)) {
              connectionEstablishedTime.set(Instant.now());
              successfulReconnections.incrementAndGet();
              consecutiveFailures.set(0);
              lastErrorType.set(null);

              log.info(
                  LogCategory.GRPC,
                  "ConnectionEstablished",
                  "Job queue connection took %s",
                  Duration.between(reconnectionStartTime.get(), connectionEstablishedTime.get()));
            }

            if (!response.hasJob()) {
              log.debug(LogCategory.GRPC, "Heartbeat", "Received heartbeat from server");
              return;
            }

            var job = response.getJob();
            String jobId = job.getJobId();

            // Handle keepalive - must ACK to register with presence store
            if (KEEPALIVE_JOB_ID.equals(jobId)) {
              log.debug(
                  LogCategory.GRPC, "Keepalive", "Received keepalive, sending ACK to register");
              sendAck(KEEPALIVE_JOB_ID);
              return;
            }

            log.debug(
                LogCategory.GRPC,
                "JobReceived",
                "Received job: %s (type: %s)",
                jobId,
                getJobType(job));

            // Process the job
            boolean success = processJob(job);

            // Send ACK only on success - if not success, job will be redelivered
            if (success) {
              sendAck(jobId);
              jobsProcessed.incrementAndGet();
            } else {
              jobsFailed.incrementAndGet();
              log.warn(
                  LogCategory.GRPC,
                  "JobNotAcked",
                  "Job %s NOT acknowledged - will be redelivered to another client",
                  jobId);
            }
          }

          @Override
          public void onError(Throwable t) {
            log.warn(LogCategory.GRPC, "StreamError", "Job queue stream error: %s", t.getMessage());
            errorRef.set(t);
            latch.countDown();
          }

          @Override
          public void onCompleted() {
            log.info(LogCategory.GRPC, "StreamCompleted", "Job queue stream completed by server");
            latch.countDown();
          }
        };

    // Open bidirectional stream
    var requestObserver = GateServiceGrpc.newStub(getChannel()).jobQueueStream(responseObserver);
    requestObserverRef.set(requestObserver);

    // Send initial keepalive to register with server
    log.debug(LogCategory.GRPC, "Init", "Sending initial keepalive to job queue...");
    requestObserver.onNext(JobQueueStreamRequest.newBuilder().setJobId(KEEPALIVE_JOB_ID).build());

    // Wait for stream to complete, with periodic hung-connection checks
    boolean completed = false;
    long startTime = System.currentTimeMillis();
    long maxDurationMs = TimeUnit.MINUTES.toMillis(STREAM_TIMEOUT_MINUTES);

    while (!completed && (System.currentTimeMillis() - startTime) < maxDurationMs) {
      completed = latch.await(HUNG_CONNECTION_THRESHOLD_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        // Check for hung connection
        checkForHungConnection();
      }
    }

    // Propagate error if any
    var error = errorRef.get();
    if (error != null) {
      throw new RuntimeException("Stream error", error);
    }
  }

  /** Send acknowledgment for a job. */
  private void sendAck(String jobId) {
    var observer = requestObserverRef.get();
    if (observer != null) {
      try {
        observer.onNext(JobQueueStreamRequest.newBuilder().setJobId(jobId).build());
        log.debug(LogCategory.GRPC, "AckSent", "Sent ACK for job: %s", jobId);
      } catch (Exception e) {
        log.error(
            LogCategory.GRPC,
            "AckFailed",
            "Failed to send ACK for job %s: %s",
            jobId,
            e.getMessage());
      }
    } else {
      log.warn(
          LogCategory.GRPC, "AckFailed", "Cannot send ACK for job %s - no active observer", jobId);
    }
  }

  /**
   * Process a job. Returns true if successfully processed.
   *
   * <p>This mirrors the job handling logic from GateClientJobStream exactly.
   */
  private boolean processJob(StreamJobsResponse value) {
    long jobStartTime = System.currentTimeMillis();

    try {
      boolean adminJob = isAdminJob(value);
      if (!adminJob && !plugin.isRunning()) {
        log.warn(
            LogCategory.GRPC,
            "JobRejected",
            "Skipping job %s because database is unavailable (only admin jobs can run)",
            value.getJobId());
        submitJobError(value.getJobId(), "Database unavailable; only admin jobs can run");
        return false;
      }

      if (value.hasListPools()) {
        plugin.listPools(value.getJobId(), value.getListPools());
      } else if (value.hasGetPoolStatus()) {
        plugin.getPoolStatus(value.getJobId(), value.getGetPoolStatus());
      } else if (value.hasGetPoolRecords()) {
        plugin.getPoolRecords(value.getJobId(), value.getGetPoolRecords());
      } else if (value.hasSearchRecords()) {
        plugin.searchRecords(value.getJobId(), value.getSearchRecords());
      } else if (value.hasGetRecordFields()) {
        plugin.readFields(value.getJobId(), value.getGetRecordFields());
      } else if (value.hasSetRecordFields()) {
        plugin.writeFields(value.getJobId(), value.getSetRecordFields());
      } else if (value.hasCreatePayment()) {
        plugin.createPayment(value.getJobId(), value.getCreatePayment());
      } else if (value.hasPopAccount()) {
        plugin.popAccount(value.getJobId(), value.getPopAccount());
      } else if (value.hasInfo()) {
        plugin.info(value.getJobId(), value.getInfo());
      } else if (value.hasShutdown()) {
        plugin.shutdown(value.getJobId(), value.getShutdown());
      } else if (value.hasLogging()) {
        plugin.logger(value.getJobId(), value.getLogging());
      } else if (value.hasExecuteLogic()) {
        plugin.executeLogic(value.getJobId(), value.getExecuteLogic());
      } else if (value.hasDiagnostics()) {
        plugin.runDiagnostics(value.getJobId(), value.getDiagnostics());
      } else if (value.hasListTenantLogs()) {
        plugin.listTenantLogs(value.getJobId(), value.getListTenantLogs());
      } else if (value.hasSetLogLevel()) {
        plugin.setLogLevel(value.getJobId(), value.getSetLogLevel());
      } else {
        log.error(
            LogCategory.GRPC, "UnknownJobType", "Unknown job type: %s", value.getUnknownFields());
      }

      long jobDuration = System.currentTimeMillis() - jobStartTime;
      log.debug(
          LogCategory.GRPC,
          "JobCompleted",
          "Processed job %s in %d ms",
          value.getJobId(),
          jobDuration);
      return true;

    } catch (UnconfiguredException e) {
      long jobDuration = System.currentTimeMillis() - jobStartTime;
      log.error(
          LogCategory.GRPC,
          "JobHandlingError",
          "Error while handling job: %s (took %d ms)",
          value.getJobId(),
          jobDuration,
          e);
      return false;
    } catch (Exception e) {
      long jobDuration = System.currentTimeMillis() - jobStartTime;
      log.error(
          LogCategory.GRPC,
          "UnexpectedJobError",
          "Unexpected error while handling job: %s (took %d ms)",
          value.getJobId(),
          jobDuration,
          e);
      return false;
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

  private boolean isAdminJob(StreamJobsResponse value) {
    return value.hasDiagnostics()
        || value.hasListTenantLogs()
        || value.hasSetLogLevel()
        || value.hasShutdown()
        || value.hasInfo();
  }

  private void submitJobError(String jobId, String message) {
    try {
      SubmitJobResultsRequest request =
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setErrorResult(
                  SubmitJobResultsRequest.ErrorResult.newBuilder().setMessage(message).build())
              .build();

      GateServiceGrpc.newBlockingStub(getChannel())
          .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .withWaitForReady()
          .submitJobResults(request);
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "SubmitJobErrorFailed",
          "Failed to submit error for job %s: %s",
          jobId,
          e.getMessage());
    }
  }

  public void stop() {
    log.info(
        LogCategory.GRPC,
        "Stopping",
        "Stopping GateClientJobQueue (total attempts: %d, successful: %d, jobs: %d/%d)",
        totalReconnectionAttempts.get(),
        successfulReconnections.get(),
        jobsProcessed.get(),
        jobsFailed.get());

    isRunning.set(false);

    // Gracefully close the stream
    var observer = requestObserverRef.get();
    if (observer != null) {
      try {
        observer.onCompleted();
      } catch (Exception e) {
        log.debug(LogCategory.GRPC, "CloseError", "Error closing stream: %s", e.getMessage());
      }
    }

    shutdown();
    log.info(LogCategory.GRPC, "GateClientJobQueueStopped", "GateClientJobQueue stopped");
  }

  @PreDestroy
  public void destroy() {
    log.info(
        LogCategory.GRPC,
        "GateClientJobQueue@PreDestroyCalled",
        "GateClientJobQueue @PreDestroy called");
    stop();
  }

  public Map<String, Object> getStreamStatus() {
    Instant lastDisconnect = lastDisconnectTime.get();
    Instant connectionEstablished = connectionEstablishedTime.get();
    Instant reconnectStart = reconnectionStartTime.get();
    Instant lastMessage = lastMessageTime.get();

    Map<String, Object> status = new HashMap<>();
    status.put("isRunning", isRunning.get());
    status.put("totalReconnectionAttempts", totalReconnectionAttempts.get());
    status.put("successfulReconnections", successfulReconnections.get());
    status.put("consecutiveFailures", consecutiveFailures.get());
    status.put("jobsProcessed", jobsProcessed.get());
    status.put("jobsFailed", jobsFailed.get());
    status.put("lastDisconnectTime", lastDisconnect != null ? lastDisconnect.toString() : null);
    status.put(
        "connectionEstablishedTime",
        connectionEstablished != null ? connectionEstablished.toString() : null);
    status.put("reconnectionStartTime", reconnectStart != null ? reconnectStart.toString() : null);
    status.put("lastErrorType", lastErrorType.get());
    status.put("lastMessageTime", lastMessage != null ? lastMessage.toString() : null);

    return status;
  }

  /** Exception for hung connection detection. */
  public static class HungConnectionException extends Exception {
    public HungConnectionException(String message) {
      super(message);
    }
  }
}
