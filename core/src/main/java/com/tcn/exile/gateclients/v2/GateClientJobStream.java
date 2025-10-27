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
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.log.LogCategory;
import com.tcn.exile.log.StructuredLogger;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Context
@Requires(property = "tcn.gate.enabled", value = "true")
public class GateClientJobStream extends GateClientAbstract
    implements StreamObserver<StreamJobsResponse> {
  private static final StructuredLogger log = new StructuredLogger(GateClientJobStream.class);

  private final PluginInterface plugin;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
  private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
  private final AtomicReference<Thread> streamThreadRef = new AtomicReference<>();
  private final AtomicReference<GateServiceGrpc.GateServiceStub> clientRef =
      new AtomicReference<>();
  private final AtomicBoolean establishedForCurrentAttempt = new AtomicBoolean(false);

  // Connection timing tracking
  private final AtomicReference<Instant> lastDisconnectTime = new AtomicReference<>();
  private final AtomicReference<Instant> reconnectionStartTime = new AtomicReference<>();
  private final AtomicReference<Instant> connectionEstablishedTime = new AtomicReference<>();
  private final AtomicLong totalReconnectionAttempts = new AtomicLong(0);
  private final AtomicLong successfulReconnections = new AtomicLong(0);
  private final AtomicReference<String> lastErrorType = new AtomicReference<>();
  private final AtomicLong consecutiveFailures = new AtomicLong(0);

  // Reconnection strategy constants
  private static final long INITIAL_RECONNECT_DELAY_MS = 1000; // 1 second
  private static final long MAX_RECONNECT_DELAY_MS = 30000; // 30 seconds max
  private static final long IMMEDIATE_RECONNECT_DELAY_MS = 100; // 100ms for network errors

  public GateClientJobStream(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  @Override
  protected void resetChannel() {
    log.info(
        LogCategory.GRPC,
        "ResetChannel",
        "Resetting static shared gRPC channel after connection failure.");

    // mark the stream for restart and wake it up if it’s sleeping
    isRunning.set(false);
    shouldReconnect.set(true);

    // interrupt the stream thread if it’s alive
    Thread streamThread = streamThreadRef.get();
    if (streamThread != null && streamThread.isAlive()) {
      log.debug(
          LogCategory.GRPC,
          "InterruptingStreamThreadForReset",
          "Interrupting job stream thread before channel reset");
      streamThread.interrupt();
    }

    super.resetChannel();
  }

  @Override
  public void start() {
    if (isUnconfigured()) {
      log.warn(
          LogCategory.GRPC, "StartupSkipped", "GateClientJobStream is unconfigured, not starting.");
      return;
    }

    if (!plugin.isRunning()) {
      log.debug(
          LogCategory.GRPC,
          "PluginNotRunning",
          "Plugin is not running (possibly due to database disconnection), skipping job stream");
      return;
    }

    if (isRunning.compareAndSet(false, true)) {
      // Set up MDC context for the stream
      StructuredLogger.setupRequestContext(tenant, null, "jobStream", null);

      log.info(LogCategory.GRPC, "Starting", "Starting GateClientJobStream");
      shouldReconnect.set(true);

      // Create managed executor service
      ExecutorService executor =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "GateClientJobStream-" + tenant);
                t.setDaemon(true); // Daemon thread to prevent JVM hanging
                return t;
              });
      executorRef.set(executor);

      executor.submit(this::maintainConnection);
    } else {
      log.debug(LogCategory.GRPC, "AlreadyRunning", "GateClientJobStream is already running");
    }
  }

  private void maintainConnection() {
    while (shouldReconnect.get() && isRunning.get()) {
      try {
        long attemptNumber = totalReconnectionAttempts.incrementAndGet();
        Instant reconnectStart = Instant.now();
        reconnectionStartTime.set(reconnectStart);
        establishedForCurrentAttempt.set(false);
        connectionEstablishedTime.set(null);

        // Calculate time since last disconnect if available
        Instant lastDisconnect = lastDisconnectTime.get();
        String disconnectTimingInfo = "";
        if (lastDisconnect != null) {
          Duration timeSinceDisconnect = Duration.between(lastDisconnect, reconnectStart);
          disconnectTimingInfo =
              String.format(
                  " (%.3f seconds since last disconnect)", timeSinceDisconnect.toMillis() / 1000.0);
        }

        log.info(
            LogCategory.GRPC,
            "EstablishingConnection",
            "Establishing job stream connection - attempt #%d%s",
            attemptNumber,
            disconnectTimingInfo);

        establishJobStream();

        // Stream returned (ended). Report uptime if it was established.
        Instant streamEnd = Instant.now();
        Instant establishedAt = connectionEstablishedTime.get();
        if (establishedAt != null && establishedForCurrentAttempt.get()) {
          Duration uptime = Duration.between(establishedAt, streamEnd);
          log.info(
              LogCategory.GRPC,
              "StreamEnded",
              "Job stream ended after %.3f seconds of uptime - attempt #%d (total successful: %d)",
              uptime.toMillis() / 1000.0,
              attemptNumber,
              successfulReconnections.get());
        } else {
          log.info(
              LogCategory.GRPC,
              "StreamEndedBeforeEstablish",
              "Job stream ended before establishment - attempt #%d (total successful: %d)",
              attemptNumber,
              successfulReconnections.get());
        }

      } catch (UnconfiguredException e) {
        log.error(
            LogCategory.GRPC,
            "ConfigurationError",
            "Configuration error in job stream, stopping reconnection attempts - attempt #%d",
            totalReconnectionAttempts.get());
        shouldReconnect.set(false);
        break;
      } catch (Exception e) {
        Instant failureTime = Instant.now();
        Instant reconnectStart = reconnectionStartTime.get();

        if (reconnectStart != null) {
          Duration attemptDuration = Duration.between(reconnectStart, failureTime);
          log.error(
              LogCategory.GRPC,
              "ConnectionAttemptFailed",
              "Job stream connection attempt #%d failed after %.3f seconds: %s",
              totalReconnectionAttempts.get(),
              attemptDuration.toMillis() / 1000.0,
              e.getMessage());
        } else {
          log.error(
              LogCategory.GRPC,
              "UnexpectedError",
              "Unexpected error in job stream connection - attempt #%d: %s",
              totalReconnectionAttempts.get(),
              e.getMessage());
        }

        if (shouldReconnect.get()) {
          long consecutiveFailureCount = consecutiveFailures.incrementAndGet();
          long delay = calculateReconnectDelay(consecutiveFailureCount, lastErrorType.get());
          try {
            waitBeforeReconnect(delay, totalReconnectionAttempts.get());
          } catch (InterruptedException ie) {
            log.info(
                LogCategory.GRPC,
                "ReconnectionWaitInterrupted",
                "Reconnection wait interrupted, stopping after %d attempts (%d successful)",
                totalReconnectionAttempts.get(),
                successfulReconnections.get());
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }
    log.info(
        LogCategory.GRPC,
        "JobStreamMaintenanceLoopEnded",
        "Job stream connection maintenance loop ended - total attempts: %d, successful: %d",
        totalReconnectionAttempts.get(),
        successfulReconnections.get());
  }

  /** Calculate reconnection delay based on attempt number and last error type */
  private long calculateReconnectDelay(long attemptNumber, String lastError) {
    // For network errors (UNAVAILABLE), reconnect almost immediately
    if ("UNAVAILABLE".equals(lastError)) {
      return IMMEDIATE_RECONNECT_DELAY_MS;
    }

    // For CANCELLED (server closed connection), use short delay
    if ("CANCELLED".equals(lastError)) {
      return Math.min(2000, INITIAL_RECONNECT_DELAY_MS * 2); // 2 seconds max
    }

    // For other errors, use exponential backoff with jitter
    long baseDelay =
        Math.min(
            INITIAL_RECONNECT_DELAY_MS * (1L << Math.min(attemptNumber - 1, 5)),
            MAX_RECONNECT_DELAY_MS);

    // Add jitter to prevent thundering herd (10% of base delay)
    long jitter = (long) (baseDelay * 0.1 * Math.random());
    return baseDelay + jitter;
  }

  private void waitBeforeReconnect(long delayMs, long attemptNumber) throws InterruptedException {
    String lastError = lastErrorType.get();
    String delayReason = getDelayReason(lastError, delayMs);

    log.info(
        LogCategory.GRPC,
        "WaitingReconnectionAttempt",
        "Waiting %.3f seconds before reconnection attempt #%d (%s) - total attempts: %d, successful: %d",
        delayMs / 1000.0,
        attemptNumber,
        delayReason,
        totalReconnectionAttempts.get(),
        successfulReconnections.get());

    Thread.sleep(delayMs);
  }

  private String getDelayReason(String errorType, long delayMs) {
    if ("UNAVAILABLE".equals(errorType)) {
      return "immediate retry for network error";
    } else if ("CANCELLED".equals(errorType)) {
      return "short delay for server disconnect";
    } else if (delayMs >= MAX_RECONNECT_DELAY_MS) {
      return "max delay reached";
    } else {
      return "exponential backoff";
    }
  }

  private void establishJobStream() throws UnconfiguredException {
    Instant streamSetupStart = Instant.now();

    GateServiceGrpc.GateServiceStub client =
        GateServiceGrpc.newStub(getChannel())
            .withDeadlineAfter(300, TimeUnit.SECONDS)
            .withWaitForReady();

    clientRef.set(client);
    log.debug(LogCategory.GRPC, "establishJobStream", "clientRef set");
    streamThreadRef.set(Thread.currentThread());

    try {
      log.debug(LogCategory.GRPC, "CreatingStream", "Creating job stream");

      Instant streamCallStart = Instant.now();
      client.streamJobs(StreamJobsRequest.newBuilder().build(), this);

      Instant streamCallComplete = Instant.now();
      Duration streamSetupDuration = Duration.between(streamSetupStart, streamCallComplete);
      log.debug(
          LogCategory.GRPC,
          "StreamCallCompleted",
          "Stream jobs call completed in %.3f seconds",
          streamSetupDuration.toMillis() / 1000.0);

      // Keep the stream alive while we should be connected
      while (shouldReconnect.get() && isRunning.get() && !Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(10000); // Check every 10 seconds
        } catch (InterruptedException e) {
          log.info(
              LogCategory.GRPC,
              "JobStreamThreadInterrupted",
              "Job stream thread interrupted, closing stream");
          Thread.currentThread().interrupt();
          break;
        }
      }

    } catch (StatusRuntimeException e) {
      Instant errorTime = Instant.now();
      Duration streamLifetime = Duration.between(streamSetupStart, errorTime);

      if (handleStatusRuntimeException(e)) {
        log.warn(
            LogCategory.GRPC,
            "ConnectionUnavailable",
            "Connection unavailable in job stream after %.3f seconds, channel reset: %s (%s)",
            streamLifetime.toMillis() / 1000.0,
            e.getMessage(),
            e.getStatus().getCode());
      } else {
        log.error(
            LogCategory.GRPC,
            "Error",
            "Error in job stream after %.3f seconds: %s (%s)",
            streamLifetime.toMillis() / 1000.0,
            e.getMessage(),
            e.getStatus().getCode());
      }
      throw e;
    } catch (Exception e) {
      Instant errorTime = Instant.now();
      Duration streamLifetime = Duration.between(streamSetupStart, errorTime);
      log.error(
          LogCategory.GRPC,
          "UnexpectedError",
          "Unexpected error in job stream after %.3f seconds: %s",
          streamLifetime.toMillis() / 1000.0,
          e.getMessage());
      throw e;
    } finally {
      clientRef.set(null);
      streamThreadRef.set(null);
    }
  }

  @Override
  public void onNext(StreamJobsResponse value) {
    long jobStartTime = System.currentTimeMillis();
    log.debug(LogCategory.GRPC, "JobReceived", "Received job: %s", value.getJobId());
    if (establishedForCurrentAttempt.compareAndSet(false, true)) {
      Instant now = Instant.now();
      connectionEstablishedTime.set(now);
      successfulReconnections.incrementAndGet();
      lastErrorType.set(null);
      consecutiveFailures.set(0);

      Instant reconnectStart = reconnectionStartTime.get();
      double secondsToEstablish = 0.0;
      if (reconnectStart != null) {
        secondsToEstablish = Duration.between(reconnectStart, now).toMillis() / 1000.0;
      }
      long attemptNumber = totalReconnectionAttempts.get();
      log.info(
          LogCategory.GRPC,
          "ConnectionEstablished",
          "Job stream connection established successfully in %.3f seconds - attempt #%d (total successful: %d)",
          secondsToEstablish,
          attemptNumber,
          successfulReconnections.get());
    }
    try {
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
    Instant disconnectTime = Instant.now();
    lastDisconnectTime.set(disconnectTime);

    // Calculate connection uptime if we have connection establishment time
    Instant connectionTime = connectionEstablishedTime.get();
    String uptimeInfo = "";
    if (connectionTime != null) {
      Duration uptime = Duration.between(connectionTime, disconnectTime);
      uptimeInfo =
          String.format(" (connection was up for %.3f seconds)", uptime.toMillis() / 1000.0);
    }

    if (t instanceof StatusRuntimeException) {
      StatusRuntimeException statusEx = (StatusRuntimeException) t;

      // Capture error type for reconnection strategy
      String errorCode = statusEx.getStatus().getCode().toString();
      lastErrorType.set(errorCode);

      if (statusEx.getStatus().getCode().toString().equals("CANCELLED")) {
        log.warn(
            LogCategory.GRPC,
            "StreamCancelled",
            "Stream cancelled by server or network issue%s, will attempt fast reconnect (total attempts: %d, successful: %d)",
            uptimeInfo,
            totalReconnectionAttempts.get(),
            successfulReconnections.get());
      } else if (statusEx.getStatus().getCode().toString().equals("UNAVAILABLE")) {
        log.warn(
            LogCategory.GRPC,
            "StreamUnavailable",
            "Stream unavailable, likely network issue or server restart%s. Will attempt immediate reconnect (total attempts: %d, successful: %d)",
            uptimeInfo,
            totalReconnectionAttempts.get(),
            successfulReconnections.get());
        resetChannel(); // Reset channel for unavailable errors
      } else if (statusEx.getStatus().getCode().toString().equals("DEADLINE_EXCEEDED")) {
        log.error(
            LogCategory.GRPC,
            "StreamDeadlineExceeded",
            "Stream deadline exceeded %s - (total attempts: %d, successful: %d)",
            uptimeInfo,
            totalReconnectionAttempts.get(),
            successfulReconnections.get(),
            statusEx);
      } else {
        // All other StatusRuntimeExceptions are unexpected and should be logged
        log.error(
            LogCategory.GRPC,
            "UnhandledStatusError",
            "Unhandled StatusRuntimeException in job stream%s. Status: %s, Message: %s (total attempts: %d, successful: %d)",
            uptimeInfo,
            statusEx.getStatus(),
            statusEx.getMessage(),
            totalReconnectionAttempts.get(),
            successfulReconnections.get(),
            statusEx);
      }
    } else {
      // For non-gRPC errors, use default backoff
      lastErrorType.set("OTHER");
      log.error(
          LogCategory.GRPC,
          "NonStatusError",
          "Job stream onError: Non-StatusRuntimeException%s (total attempts: %d, successful: %d)",
          uptimeInfo,
          totalReconnectionAttempts.get(),
          successfulReconnections.get(),
          t);
    }

    // Clear client reference
    clientRef.set(null);
    isRunning.set(false);
    shouldReconnect.set(true);
  }

  @Override
  public void onCompleted() {
    Instant disconnectTime = Instant.now();
    lastDisconnectTime.set(disconnectTime);

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
    clientRef.set(null);
    isRunning.set(false);
    shouldReconnect.set(true);
  }

  public boolean isStreamActive() {
    return clientRef.get() != null;
  }

  public boolean isRunning() {
    return isActive();
  }

  public void stop() {
    log.info(
        LogCategory.GRPC,
        "Stopping",
        "Stopping GateClientJobStream (total attempts: %d, successful: %d)",
        totalReconnectionAttempts.get(),
        successfulReconnections.get());

    shouldReconnect.set(false);
    isRunning.set(false);

    Thread streamThread = streamThreadRef.get();
    if (streamThread != null && streamThread.isAlive()) {
      log.debug(LogCategory.GRPC, "InterruptingStreamThread", "Interrupting stream thread");
      streamThread.interrupt();
    }

    ExecutorService executor = executorRef.get();
    if (executor != null && !executor.isShutdown()) {
      log.debug(LogCategory.GRPC, "ShuttingDownExecutorService", "Shutting down executor service");
      executor.shutdown();
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          log.warn(
              LogCategory.GRPC,
              "ExecutorDidNotTerminateGracefully",
              "Executor did not terminate gracefully, forcing shutdown");
          executor.shutdownNow();
          if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            log.error(
                LogCategory.GRPC,
                "ExecutorFailedToTerminateEvenAfterForcedShutdown",
                "Executor failed to terminate even after forced shutdown");
          }
        }
      } catch (InterruptedException e) {
        log.warn(
            LogCategory.GRPC,
            "InterruptedWhileWaitingForExecutorTermination",
            "Interrupted while waiting for executor termination");
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    shutdown();

    // Clear MDC context
    StructuredLogger.clearContext();

    log.info(LogCategory.GRPC, "GateClientJobStreamStopped", "GateClientJobStream stopped");
  }

  @PreDestroy
  public void destroy() {
    log.info(
        LogCategory.GRPC,
        "GateClientJobStream@PreDestroyCalled",
        "GateClientJobStream @PreDestroy called");
    stop();
  }

  public boolean isActive() {
    return isRunning.get() && shouldReconnect.get();
  }

  public Map<String, Object> getStreamStatus() {
    ExecutorService executor = executorRef.get();
    Thread streamThread = streamThreadRef.get();
    GateServiceGrpc.GateServiceStub client = clientRef.get();

    // Add timing information to status
    Instant lastDisconnect = lastDisconnectTime.get();
    Instant connectionEstablished = connectionEstablishedTime.get();
    Instant reconnectStart = reconnectionStartTime.get();

    Map<String, Object> status = new HashMap<>();
    status.put("isRunning", isRunning.get());
    status.put("shouldReconnect", shouldReconnect.get());
    status.put("executorShutdown", executor == null || executor.isShutdown());
    status.put("executorTerminated", executor == null || executor.isTerminated());
    status.put("streamThreadAlive", streamThread != null && streamThread.isAlive());
    status.put("streamThreadInterrupted", streamThread != null && streamThread.isInterrupted());
    status.put("clientActive", client != null);
    status.put("totalReconnectionAttempts", totalReconnectionAttempts.get());
    status.put("successfulReconnections", successfulReconnections.get());
    status.put("consecutiveFailures", consecutiveFailures.get());
    status.put("lastDisconnectTime", lastDisconnect != null ? lastDisconnect.toString() : null);
    status.put(
        "connectionEstablishedTime",
        connectionEstablished != null ? connectionEstablished.toString() : null);
    status.put("reconnectionStartTime", reconnectStart != null ? reconnectStart.toString() : null);
    status.put("lastErrorType", lastErrorType.get());

    return status;
  }
}
