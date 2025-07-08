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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

  public GateClientJobStream(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
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
        log.info(LogCategory.GRPC, "EstablishingConnection", "Establishing job stream connection");
        establishJobStream();
      } catch (UnconfiguredException e) {
        log.error(
            LogCategory.GRPC,
            "ConfigurationError",
            "Configuration error in job stream, stopping reconnection attempts");
        shouldReconnect.set(false);
        break;
      } catch (Exception e) {
        log.error(LogCategory.GRPC, "UnexpectedError", "Unexpected error in job stream connection");
        if (shouldReconnect.get()) {
          log.info(
              LogCategory.GRPC,
              "WaitingReconnectionAttempt",
              "Waiting 30 seconds before reconnection attempt");
          try {
            Thread.sleep(30000);
          } catch (InterruptedException ie) {
            log.info(
                LogCategory.GRPC,
                "ReconnectionWaitInterrupted",
                "Reconnection wait interrupted, stopping");
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }
    log.info(
        LogCategory.GRPC,
        "JobStreamMaintenanceLoopEnded",
        "Job stream connection maintenance loop ended");
  }

  private void establishJobStream() throws UnconfiguredException {
    GateServiceGrpc.GateServiceStub client =
        GateServiceGrpc.newStub(getChannel())
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .withWaitForReady();

    clientRef.set(client);
    streamThreadRef.set(Thread.currentThread());

    try {
      log.debug(LogCategory.GRPC, "CreatingStream", "Creating job stream");
      client.streamJobs(StreamJobsRequest.newBuilder().build(), this);

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
      if (handleStatusRuntimeException(e)) {
        log.warn(
            LogCategory.GRPC,
            "ConnectionUnavailable",
            "Connection unavailable in job stream, channel reset");
      } else {
        log.error(
            LogCategory.GRPC,
            "Error",
            "Error in job stream: {} ({})",
            e.getMessage(),
            e.getStatus().getCode());
      }
      throw e;
    } catch (Exception e) {
      log.error(LogCategory.GRPC, "UnexpectedError", "Unexpected error in job stream");
      throw e;
    } finally {
      clientRef.set(null);
      streamThreadRef.set(null);
    }
  }

  @Override
  public void onNext(StreamJobsResponse value) {
    log.debug(LogCategory.GRPC, "JobReceived", "Received job: {}", value.getJobId());
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
      } else {
        log.error(
            LogCategory.GRPC, "UnknownJobType", "Unknown job type: {}", value.getUnknownFields());
      }
    } catch (UnconfiguredException e) {
      log.error(
          LogCategory.GRPC,
          "JobHandlingError",
          "Error while handling job: {}",
          value.getJobId(),
          e);
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "UnexpectedJobError",
          "Unexpected error while handling job: {}",
          value.getJobId(),
          e);
    }
  }

  @Override
  public void onError(Throwable t) {
    if (t instanceof StatusRuntimeException) {
      StatusRuntimeException statusEx = (StatusRuntimeException) t;
      log.error(
          LogCategory.GRPC,
          "StreamError",
          "Job stream onError: Status={}, Message={}",
          statusEx.getStatus(),
          statusEx.getMessage(),
          statusEx);

      if (statusEx.getStatus().getCode() == Status.Code.CANCELLED) {
        log.warn(
            LogCategory.GRPC,
            "StreamCancelled",
            "Stream cancelled by server or network issue, will attempt reconnect if enabled");
      } else if (statusEx.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        log.warn(
            LogCategory.GRPC,
            "StreamUnavailable",
            "Stream unavailable, likely network issue or server restart. Will attempt reconnect if enabled");
        resetChannel(); // Reset channel for unavailable errors
      } else {
        log.error(
            LogCategory.GRPC,
            "UnhandledStatusError",
            "Unhandled StatusRuntimeException in job stream. Status: {}, Message: {}",
            statusEx.getStatus(),
            statusEx.getMessage());
      }
    } else {
      log.error(
          LogCategory.GRPC, "NonStatusError", "Job stream onError: Non-StatusRuntimeException", t);
    }

    // Clear client reference
    clientRef.set(null);
    isRunning.set(false);
    shouldReconnect.set(true);
  }

  @Override
  public void onCompleted() {
    log.info(
        LogCategory.GRPC,
        "StreamCompleted",
        "Job stream onCompleted: Server closed the stream gracefully.");
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
    log.info(LogCategory.GRPC, "Stopping", "Stopping GateClientJobStream");

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

    return Map.of(
        "isRunning",
        isRunning.get(),
        "shouldReconnect",
        shouldReconnect.get(),
        "executorShutdown",
        executor == null || executor.isShutdown(),
        "executorTerminated",
        executor == null || executor.isTerminated(),
        "streamThreadAlive",
        streamThread != null && streamThread.isAlive(),
        "streamThreadInterrupted",
        streamThread != null && streamThread.isInterrupted(),
        "clientActive",
        client != null);
  }
}
