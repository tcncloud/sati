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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Bidirectional job streaming with acknowledgment using the JobQueueStream API. */
public class GateClientJobQueue extends GateClientAbstract {
  private static final StructuredLogger log = new StructuredLogger(GateClientJobQueue.class);
  private static final int DEFAULT_TIMEOUT_SECONDS = 300;
  private static final String KEEPALIVE_JOB_ID = "keepalive";

  private final PluginInterface plugin;
  private final AtomicLong jobsProcessed = new AtomicLong(0);
  private final AtomicLong jobsFailed = new AtomicLong(0);

  // Stream observer for sending ACKs
  private final AtomicReference<StreamObserver<JobQueueStreamRequest>> requestObserverRef =
      new AtomicReference<>();

  public GateClientJobQueue(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  @Override
  protected String getStreamName() {
    return "JobQueue";
  }

  @Override
  protected void onStreamDisconnected() {
    requestObserverRef.set(null);
  }

  @Override
  protected void runStream()
      throws UnconfiguredException, InterruptedException, HungConnectionException {
    var latch = new CountDownLatch(1);
    var errorRef = new AtomicReference<Throwable>();
    var firstResponseReceived = new AtomicBoolean(false);

    var responseObserver =
        new StreamObserver<JobQueueStreamResponse>() {
          @Override
          public void onNext(JobQueueStreamResponse response) {
            lastMessageTime.set(Instant.now());

            if (firstResponseReceived.compareAndSet(false, true)) {
              onConnectionEstablished();
            }

            if (!response.hasJob()) {
              log.debug(LogCategory.GRPC, "Heartbeat", "Received heartbeat from server");
              return;
            }

            var job = response.getJob();
            String jobId = job.getJobId();

            // Handle keepalive — must ACK to register with presence store
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

            if (processJob(job)) {
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

    awaitStreamWithHungDetection(latch);

    var error = errorRef.get();
    if (error != null) {
      throw new RuntimeException("Stream error", error);
    }
  }

  // ---------------------------------------------------------------------------
  // ACK management
  // ---------------------------------------------------------------------------

  /** Send acknowledgment for a job. */
  private void sendAck(String jobId) {
    var observer = requestObserverRef.get();
    if (observer == null) {
      log.warn(
          LogCategory.GRPC, "AckFailed", "Cannot send ACK for job %s - no active observer", jobId);
      return;
    }
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
  }

  // ---------------------------------------------------------------------------
  // Job processing
  // ---------------------------------------------------------------------------

  /** Process a job by dispatching to the plugin. Returns true if successfully processed. */
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

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void stop() {
    log.info(
        LogCategory.GRPC,
        "Stopping",
        "Stopping GateClientJobQueue (total attempts: %d, successful: %d, jobs: %d/%d)",
        totalReconnectionAttempts.get(),
        successfulReconnections.get(),
        jobsProcessed.get(),
        jobsFailed.get());

    var observer = requestObserverRef.get();
    if (observer != null) {
      try {
        observer.onCompleted();
      } catch (Exception e) {
        log.debug(LogCategory.GRPC, "CloseError", "Error closing stream: %s", e.getMessage());
      }
    }

    doStop();
    log.info(LogCategory.GRPC, "Stopped", "GateClientJobQueue stopped");
  }

  @PreDestroy
  public void destroy() {
    stop();
  }

  public Map<String, Object> getStreamStatus() {
    Map<String, Object> status = buildStreamStatus();
    status.put("jobsProcessed", jobsProcessed.get());
    status.put("jobsFailed", jobsFailed.get());
    return status;
  }
}
