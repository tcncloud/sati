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
package com.tcn.exile.gateclients.v2;

import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsRequest;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest;
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.log.LogCategory;
import com.tcn.exile.log.StructuredLogger;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class GateClientJobStream extends GateClientAbstract
    implements StreamObserver<StreamJobsResponse> {
  private static final StructuredLogger log = new StructuredLogger(GateClientJobStream.class);
  private static final int DEFAULT_TIMEOUT_SECONDS = 300;

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

  public GateClientJobStream(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  private boolean channelIsDead(ConnectivityState state) {
    return state.compareTo(ConnectivityState.TRANSIENT_FAILURE) == 0
        || state.compareTo(ConnectivityState.SHUTDOWN) == 0;
  }

  private boolean lastJobReceivedTooLongAgo(Instant lastMessageTime) {
    return lastMessageTime != null
        && lastMessageTime.isBefore(Instant.now().minus(45, ChronoUnit.SECONDS));
  }

  private void blowupIfOurConnectionIsHung(ManagedChannel channel) throws Exception {
    var state = channel.getState(false);
    if (channelIsDead(state)) {
      throw new Exception("JobStream channel is in state " + state.toString());
    }
    var lastJobTime = lastMessageTime.get();
    var startOfStream = connectionEstablishedTime.get();

    // if we haven't received a job ever, then test when we started the stream
    if (lastJobTime == null && lastJobReceivedTooLongAgo(startOfStream)) {
      throw new Exception(
          "JobStream never received any messages since start time at: "
              + startOfStream.toString()
              + " connection is hung");
    }
    if (lastJobReceivedTooLongAgo(lastJobTime)) {
      throw new Exception(
          "JobStream received last job too long ago at: "
              + lastJobTime.toString()
              + " connection is hung");
    }
  }

  public void start() {
    if (isUnconfigured()) {
      log.warn(LogCategory.GRPC, "NOOP", "JobStream is unconfigured, cannot stream jobs");
      return;
    }

    ExecutorService executorService = Executors.newFixedThreadPool(2);
    ExecutorCompletionService<Void> completionService =
        new ExecutorCompletionService<>(executorService);

    try {
      log.debug(LogCategory.GRPC, "Init", "JobStream task started, checking configuration status");

      log.debug(LogCategory.GRPC, "Start", "Starting JobStream");
      reconnectionStartTime.set(Instant.now());
      ManagedChannel channel = getChannel();

      var client =
          GateServiceGrpc.newBlockingStub(channel)
              .withWaitForReady()
              .streamJobs(StreamJobsRequest.newBuilder().build());

      connectionEstablishedTime.set(Instant.now());
      successfulReconnections.incrementAndGet();
      isRunning.set(true);
      consecutiveFailures.set(0);
      lastErrorType.set(null);

      log.info(
          LogCategory.GRPC,
          "ConnectionEstablished",
          "Job stream connection took {}",
          Duration.between(reconnectionStartTime.get(), connectionEstablishedTime.get()));

      completionService.submit(
          () -> {
            client.forEachRemaining(this::onNext);
            return null;
          });
      completionService.submit(
          () -> {
            while (isRunning.get()) {
              Thread.sleep(45000);
              blowupIfOurConnectionIsHung(channel);
            }
            return null;
          });
      // blow up or wait
      completionService.take();

    } catch (Exception e) {
      log.error(LogCategory.GRPC, "JobStream", "error streaming jobs from server: {}", e);
      lastErrorType.set(e.getClass().getSimpleName());
      if (connectionEstablishedTime.get() == null) {
        consecutiveFailures.incrementAndGet();
      }
    } finally {
      totalReconnectionAttempts.incrementAndGet();
      lastDisconnectTime.set(Instant.now());
      isRunning.set(false);
      log.debug(LogCategory.GRPC, "Complete", "Job stream done");

      executorService.shutdownNow();
    }
  }

  @Override
  public void onNext(StreamJobsResponse value) {
    long jobStartTime = System.currentTimeMillis();
    log.debug(LogCategory.GRPC, "JobReceived", "Received job: %s", value.getJobId());
    lastMessageTime.set(Instant.now());

    try {
      boolean adminJob = isAdminJob(value);
      if (!adminJob && !plugin.isRunning()) {
        log.warn(
            LogCategory.GRPC,
            "JobRejected",
            "Skipping job %s because database is unavailable (admin-only mode)",
            value.getJobId());
        submitJobError(value.getJobId(), "Database unavailable; admin-only mode");
        return;
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
    } catch (UnconfiguredException e) {
      long jobDuration = System.currentTimeMillis() - jobStartTime;
      log.error(
          LogCategory.GRPC,
          "JobHandlingError",
          "Error while handling job: %s (took %d ms)",
          value.getJobId(),
          jobDuration,
          e);
    } catch (Exception e) {
      long jobDuration = System.currentTimeMillis() - jobStartTime;
      log.error(
          LogCategory.GRPC,
          "UnexpectedJobError",
          "Unexpected error while handling job: %s (took %d ms)",
          value.getJobId(),
          jobDuration,
          e);
    }
  }

  @Override
  public void onError(Throwable t) {
    log.error(LogCategory.GRPC, "JobStreamError", "onError received: {}", t);
  }

  @Override
  public void onCompleted() {
    Instant disconnectTime = Instant.now();
    lastDisconnectTime.set(disconnectTime);
    lastMessageTime.set(disconnectTime);

    // Calculate connection uptime if we have connection establishment time
    Instant connectionTime = connectionEstablishedTime.get();
    String uptimeInfo = "";
    if (connectionTime != null) {
      Duration uptime = Duration.between(connectionTime, disconnectTime);
      uptimeInfo =
          String.format(" (connection was up for %.3f seconds)", uptime.toMillis() / 1000.0);
    }

    log.info(
        LogCategory.GRPC,
        "StreamCompleted",
        "Job stream onCompleted: Server closed the stream gracefully%s (total attempts: %d, successful: %d)",
        uptimeInfo,
        totalReconnectionAttempts.get(),
        successfulReconnections.get());
  }

  public void stop() {
    log.info(
        LogCategory.GRPC,
        "Stopping",
        "Stopping GateClientJobStream (total attempts: %d, successful: %d)",
        totalReconnectionAttempts.get(),
        successfulReconnections.get());

    shutdown();
    log.info(LogCategory.GRPC, "GateClientJobStreamStopped", "GateClientJobStream stopped");
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

  @PreDestroy
  public void destroy() {
    log.info(
        LogCategory.GRPC,
        "GateClientJobStream@PreDestroyCalled",
        "GateClientJobStream @PreDestroy called");
    stop();
  }

  public Map<String, Object> getStreamStatus() {
    // Add timing information to status
    Instant lastDisconnect = lastDisconnectTime.get();
    Instant connectionEstablished = connectionEstablishedTime.get();
    Instant reconnectStart = reconnectionStartTime.get();
    Instant lastMessage = lastMessageTime.get();

    Map<String, Object> status = new HashMap<>();
    status.put("isRunning", isRunning.get());
    status.put("totalReconnectionAttempts", totalReconnectionAttempts.get());
    status.put("successfulReconnections", successfulReconnections.get());
    status.put("consecutiveFailures", consecutiveFailures.get());
    status.put("lastDisconnectTime", lastDisconnect != null ? lastDisconnect.toString() : null);
    status.put(
        "connectionEstablishedTime",
        connectionEstablished != null ? connectionEstablished.toString() : null);
    status.put("reconnectionStartTime", reconnectStart != null ? reconnectStart.toString() : null);
    status.put("lastErrorType", lastErrorType.get());
    status.put("lastMessageTime", lastMessage != null ? lastMessage.toString() : null);

    return status;
  }
}
