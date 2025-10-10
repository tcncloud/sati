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
package com.tcn.exile.demo.single;

import build.buf.gen.tcnapi.exile.gate.v2.ExileAgentCall;
import build.buf.gen.tcnapi.exile.gate.v2.ExileAgentResponse;
import build.buf.gen.tcnapi.exile.gate.v2.ExileTelephonyResult;
import build.buf.gen.tcnapi.exile.gate.v2.LogRequest;
import build.buf.gen.tcnapi.exile.gate.v2.StreamJobsResponse;
import build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest;
import ch.qos.logback.classic.LoggerContext;
import com.tcn.exile.config.DiagnosticsService;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.gateclients.v2.GateClient;
import com.tcn.exile.memlogger.LogShipper;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.plugin.PluginInterface;
import com.tcn.exile.plugin.PluginStatus;
import io.micronaut.context.ApplicationContext;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemoPlugin implements PluginInterface, LogShipper {
  private static final Logger log = LoggerFactory.getLogger(DemoPlugin.class);
  private boolean running = false;

  GateClient gateClient;
  private PluginConfigEvent pluginConfig;
  private String tenantKey;
  private DiagnosticsService diagnosticsService;

  public DemoPlugin(
      String tenantKey, GateClient gateClient, ApplicationContext applicationContext) {
    this.gateClient = gateClient;
    this.running = true;
    this.tenantKey = tenantKey;
    this.diagnosticsService = new DiagnosticsService(applicationContext);
  }

  @Override
  public String getName() {
    return "DemoPlugin";
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public PluginStatus getPluginStatus() {
    return new PluginStatus(
        getName(),
        running,
        100, // queueMaxSize
        0, // queueCompletedJobs
        0, // queueActiveCount
        new HashMap<>(), // internalConfig
        new HashMap<>() // internalStatus
        );
  }

  @Override
  public void listPools(String jobId, StreamJobsResponse.ListPoolsRequest listPools)
      throws UnconfiguredException {
    log.info("Tenant: {} - Listing pools for job {}", tenantKey, jobId);
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setListPoolsResult(
                SubmitJobResultsRequest.ListPoolsResult.newBuilder()
                    .addPools(
                        build.buf.gen.tcnapi.exile.core.v2.Pool.newBuilder()
                            .setPoolId("A")
                            .setDescription("Pool with id A")
                            .setStatus(build.buf.gen.tcnapi.exile.core.v2.Pool.PoolStatus.READY)
                            .build())
                    .build())
            .build());
  }

  @Override
  public void getPoolStatus(String jobId, StreamJobsResponse.GetPoolStatusRequest request)
      throws UnconfiguredException {
    log.info("Tenant: {} - Getting pool status for job={} and pool={}", tenantKey, jobId, request);
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setGetPoolStatusResult(
                SubmitJobResultsRequest.GetPoolStatusResult.newBuilder()
                    .setPool(
                        build.buf.gen.tcnapi.exile.core.v2.Pool.newBuilder()
                            .setPoolId(request.getPoolId())
                            .setStatus(build.buf.gen.tcnapi.exile.core.v2.Pool.PoolStatus.READY)
                            .build())
                    .build())
            .build());
  }

  @Override
  public void getPoolRecords(String jobId, StreamJobsResponse.GetPoolRecordsRequest request)
      throws UnconfiguredException {
    log.info("Tenant: {} - Getting pool records for job {} and pool {}", tenantKey, jobId, request);
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setGetPoolRecordsResult(
                SubmitJobResultsRequest.GetPoolRecordsResult.newBuilder()
                    .addRecords(
                        build.buf.gen.tcnapi.exile.core.v2.Record.newBuilder()
                            .setPoolId(request.getPoolId())
                            .setRecordId("blue")
                            .setJsonRecordPayload("{\"f1\": \"foo\"}")
                            .build())
                    .addRecords(
                        build.buf.gen.tcnapi.exile.core.v2.Record.newBuilder()
                            .setPoolId(request.getPoolId())
                            .setRecordId("red")
                            .setJsonRecordPayload("{\"f2\": \"bar\"}")
                            .build())
                    .build())
            .build());
  }

  @Override
  public void handleAgentCall(ExileAgentCall exileAgentCall) {
    log.info("Tenant: {} - Handling agent call for job {}", tenantKey, exileAgentCall);
  }

  @Override
  public void handleTelephonyResult(ExileTelephonyResult exileTelephonyResult) {
    log.info("Tenant: {} - Handling telephony result for job {}", tenantKey, exileTelephonyResult);
  }

  @Override
  public void handleAgentResponse(ExileAgentResponse exileAgentResponse) {
    log.info("Tenant: {} - Handling agent response for {}", tenantKey, exileAgentResponse);
  }

  @Override
  public void handleTransferInstance(
      build.buf.gen.tcnapi.exile.gate.v2.ExileTransferInstance exileTransferInstance) {
    log.info("Tenant: {} - Handling transfer instance for {}", tenantKey, exileTransferInstance);
  }

  @Override
  public void handleCallRecording(
      build.buf.gen.tcnapi.exile.gate.v2.ExileCallRecording exileCallRecording) {
    log.info("Tenant: {} - Handling call recording for {}", tenantKey, exileCallRecording);
  }

  @Override
  public void searchRecords(String jobId, StreamJobsResponse.SearchRecordsRequest searchRecords) {}

  @Override
  public void readFields(String jobId, StreamJobsResponse.GetRecordFieldsRequest getRecordFields) {
    log.info(
        "Tenant: {} - Reading fields for job {} and record {}",
        tenantKey,
        jobId,
        getRecordFields.getRecordId());
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setGetRecordFieldsResult(
                SubmitJobResultsRequest.GetRecordFieldsResult.newBuilder()
                    .addFields(
                        build.buf.gen.tcnapi.exile.core.v2.Field.newBuilder()
                            .setFieldName("foo")
                            .setFieldValue("bar")
                            .setRecordId(getRecordFields.getRecordId())
                            .build())
                    .build())
            .build());
  }

  @Override
  public void writeFields(String jobId, StreamJobsResponse.SetRecordFieldsRequest setRecordFields) {
    log.info(
        "Tenant: {} - Writing fields for job {} and record {}",
        tenantKey,
        jobId,
        setRecordFields.getRecordId());
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setSetRecordFieldsResult(
                SubmitJobResultsRequest.SetRecordFieldsResult.newBuilder().build())
            .build());
  }

  @Override
  public void createPayment(String jobId, StreamJobsResponse.CreatePaymentRequest createPayment) {
    log.info(
        "Tenant: {} - Creating payment for job {} and record {}",
        tenantKey,
        jobId,
        createPayment.getRecordId());
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setCreatePaymentResult(
                SubmitJobResultsRequest.CreatePaymentResult.newBuilder().build())
            .build());
  }

  @Override
  public void popAccount(String jobId, StreamJobsResponse.PopAccountRequest popAccount) {
    log.info(
        "Tenant: {} - Popping account for job {} and record {}",
        tenantKey,
        jobId,
        popAccount.getRecordId());
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setPopAccountResult(SubmitJobResultsRequest.PopAccountResult.newBuilder().build())
            .build());
  }

  private String getServerName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "Unknown";
    }
  }

  private String getVersion() {
    var ret = this.getClass().getPackage().getImplementationVersion();
    return ret == null ? "Unknown" : ret;
  }

  @Override
  public void info(String jobId, StreamJobsResponse.InfoRequest info) {
    log.info("Tenant: {} - Info for job {}", tenantKey, jobId);
    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setInfoResult(
                SubmitJobResultsRequest.InfoResult.newBuilder()
                    .setServerName(getServerName())
                    .setCoreVersion(com.tcn.exile.gateclients.v2.BuildVersion.getBuildVersion())
                    .setPluginName("DemoPlugin")
                    .setPluginVersion(getVersion())
                    .build())
            .build());
  }

  public SubmitJobResultsRequest.InfoResult info() {
    return SubmitJobResultsRequest.InfoResult.newBuilder()
        .setServerName(getServerName())
        .setCoreVersion(com.tcn.exile.gateclients.v2.BuildVersion.getBuildVersion())
        .setPluginName("DemoPlugin")
        .setPluginVersion(getVersion())
        .build();
  }

  @Override
  public void shutdown(String jobId, StreamJobsResponse.SeppukuRequest shutdown) {
    log.warn("Tenant: {} - Seppuku requested for job: {}", tenantKey, jobId);

    // Try to submit acknowledgment, but don't let failure prevent shutdown
    try {
      gateClient.submitJobResults(
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setShutdownResult(SubmitJobResultsRequest.SeppukuResult.newBuilder().build())
              .build());
      log.warn(
          "Tenant: {} - Seppuku acknowledged for job: {}, initiating graceful shutdown",
          tenantKey,
          jobId);
    } catch (Exception e) {
      log.warn(
          "Tenant: {} - Failed to acknowledge seppuku for job: {}, but proceeding with shutdown anyway: {}",
          tenantKey,
          jobId,
          e.getMessage());
    }

    // Execute shutdown in separate thread to allow any gRPC response to be sent
    try {
      Thread shutdownThread =
          new Thread(
              () -> {
                try {
                  log.warn(
                      "Tenant: {} - Shutdown thread started, waiting 2 seconds before termination",
                      tenantKey);

                  // Give time for any response to be sent back to the server
                  Thread.sleep(2000);

                  log.warn("Tenant: {} - Executing seppuku - terminating application", tenantKey);

                  // Perform graceful shutdown
                  System.exit(0);

                } catch (InterruptedException e) {
                  log.error(
                      "Tenant: {} - Seppuku interrupted, forcing immediate shutdown", tenantKey);
                  Thread.currentThread().interrupt();
                  System.exit(1);
                } catch (Exception e) {
                  log.error(
                      "Tenant: {} - Unexpected error during seppuku, forcing immediate shutdown: {}",
                      tenantKey,
                      e.getMessage());
                  System.exit(1);
                }
              },
              "SeppukuThread-" + tenantKey);

      shutdownThread.setDaemon(false); // Ensure JVM waits for this thread
      shutdownThread.start();

      log.warn("Tenant: {} - Seppuku thread started, shutdown sequence initiated", tenantKey);
    } catch (Exception e) {
      log.error(
          "Tenant: {} - Failed to start shutdown thread, forcing immediate shutdown: {}",
          tenantKey,
          e.getMessage());
      // If we can't even start the thread, exit immediately
      System.exit(1);
    }
  }

  @Override
  public void logger(String jobId, StreamJobsResponse.LoggingRequest logRequest) {
    log.debug(
        "Tenant: {} - Received log request {} stream {} payload: {}",
        tenantKey,
        jobId,
        logRequest.getStreamLogs(),
        logRequest.getLoggerLevelsList());
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    for (var logger : logRequest.getLoggerLevelsList()) {
      log.debug(
          "Tenant: {} - Setting logger {} to level {}",
          tenantKey,
          logger.getLoggerName(),
          logger.getLoggerLevel());
      var v = loggerContext.getLogger(logger.getLoggerName());
      if (v != null) {
        if (logger.getLoggerLevel()
            == StreamJobsResponse.LoggingRequest.LoggerLevel.Level.DISABLED) {
          v.setLevel(ch.qos.logback.classic.Level.OFF);
        } else {
          v.setLevel(ch.qos.logback.classic.Level.toLevel(logger.getLoggerLevel().name()));
        }
      } else {
        log.warn("Tenant: {} - Logger {} not found", tenantKey, logger.getLoggerName());
      }
    }
    if (logRequest.getStreamLogs()) {
      MemoryAppenderInstance.getInstance().enableLogShipper(this);
    } else {
      MemoryAppenderInstance.getInstance().disableLogShipper();
    }

    gateClient.submitJobResults(
        SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setLoggingResult(SubmitJobResultsRequest.LoggingResult.newBuilder().build())
            .build());
  }

  /** Helper method to list all available loggers for debugging */
  private void listAvailableLoggers() {
    try {
      LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
      log.info("Tenant: {} - Available loggers:", tenantKey);

      // List some common loggers
      for (ch.qos.logback.classic.Logger logger : loggerContext.getLoggerList()) {
        if (logger.getLevel() != null
            || logger.getName().contains("tcn")
            || logger.getName().contains("exile")
            || logger.getName().equals("ROOT")) {
          log.info(
              "Tenant: {} - Logger: '{}' - Level: {}",
              tenantKey,
              logger.getName(),
              logger.getLevel() != null ? logger.getLevel().toString() : "INHERITED");
        }
      }
    } catch (Exception e) {
      log.warn("Tenant: {} - Error listing loggers: {}", tenantKey, e.getMessage());
    }
  }

  @Override
  public void executeLogic(String jobId, StreamJobsResponse.ExecuteLogicRequest executeLogic) {}

  @Override
  public void setConfig(PluginConfigEvent config) {
    this.pluginConfig = config;
    if (this.pluginConfig == null) {
      this.running = false;
    }
    if (config.isUnconfigured()) {
      running = false;
    }
    running = true;
  }

  @Override
  public void shipLogs(List<String> payload) {
    log.info("Tenant: {} - Ship logs", tenantKey);
    if (payload == null || payload.isEmpty()) {
      return;
    }
    String combinedPayload = String.join("\n", payload);
    gateClient.log(LogRequest.newBuilder().setPayload(combinedPayload).build());
  }

  @Override
  public void stop() {
    log.info("Tenant: {} - Stopping shipping logs plugin", tenantKey);
    MemoryAppenderInstance.getInstance().disableLogShipper();
  }

  @Override
  public void runDiagnostics(
      String jobId, StreamJobsResponse.DiagnosticsRequest diagnosticsRequest) {
    log.info("Tenant: {} - Running diagnostics for job {}", tenantKey, jobId);

    try {
      build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.DiagnosticsResult diagnostics =
          null;
      if (diagnosticsService != null) {
        // Then collect system diagnostics for the response
        diagnostics = diagnosticsService.collectSystemDiagnostics();
      } else {
        log.warn("DiagnosticsService is null, cannot collect system diagnostics");
        // Create empty diagnostics result if service is unavailable
        diagnostics = SubmitJobResultsRequest.DiagnosticsResult.newBuilder().build();
      }

      // Submit diagnostics results back to gate
      gateClient.submitJobResults(
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setDiagnosticsResult(diagnostics)
              .build());
    } catch (Exception e) {
      log.error("Error running diagnostics", e);
      // Return empty diagnostics result on error
      gateClient.submitJobResults(
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setDiagnosticsResult(SubmitJobResultsRequest.DiagnosticsResult.newBuilder().build())
              .build());
    }
  }

  @Override
  public void listTenantLogs(
      String jobId, StreamJobsResponse.ListTenantLogsRequest listTenantLogsRequest) {
    log.info("Tenant: {} - Listing tenant logs for job {}", tenantKey, jobId);

    try {
      // Use DiagnosticsService to collect tenant logs with time range filtering
      SubmitJobResultsRequest.ListTenantLogsResult tenantLogsResult =
          diagnosticsService.collectTenantLogs(listTenantLogsRequest);

      // Submit tenant logs results back to gate
      gateClient.submitJobResults(
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setListTenantLogsResult(tenantLogsResult)
              .build());
    } catch (Exception e) {
      log.error("Error listing tenant logs for job {}: {}", jobId, e.getMessage(), e);

      // Return empty log result on error
      try {
        gateClient.submitJobResults(
            SubmitJobResultsRequest.newBuilder()
                .setJobId(jobId)
                .setEndOfTransmission(true)
                .setListTenantLogsResult(
                    SubmitJobResultsRequest.ListTenantLogsResult.newBuilder().build())
                .build());
        log.debug("Submitted empty result for failed job: {}", jobId);
      } catch (Exception submitError) {
        log.error(
            "Failed to submit error result for job {}: {}",
            jobId,
            submitError.getMessage(),
            submitError);
      }
    }
  }

  @Override
  public void setLogLevel(String jobId, StreamJobsResponse.SetLogLevelRequest setLogLevelRequest) {
    log.info(
        "Tenant: {} - Setting log level for job {} and logger {} to level {}",
        tenantKey,
        jobId,
        setLogLevelRequest.getLog(),
        setLogLevelRequest.getLogLevel());

    try {
      build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.SetLogLevelResult
          setLogLevelResult = null;
      if (diagnosticsService != null) {
        setLogLevelResult = diagnosticsService.setLogLevelWithTenant(setLogLevelRequest, tenantKey);
      } else {
        log.warn("DiagnosticsService is null, cannot set log level");

        java.time.Instant now = java.time.Instant.now();
        com.google.protobuf.Timestamp updateTime =
            com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();

        SubmitJobResultsRequest.SetLogLevelResult.Tenant tenant =
            SubmitJobResultsRequest.SetLogLevelResult.Tenant.newBuilder()
                .setName(tenantKey)
                .setSatiVersion(com.tcn.exile.gateclients.v2.BuildVersion.getBuildVersion())
                .setPluginVersion(getVersion())
                .setUpdateTime(updateTime)
                .setConnectedGate(getServerName())
                .build();

        setLogLevelResult =
            SubmitJobResultsRequest.SetLogLevelResult.newBuilder().setTenant(tenant).build();
      }

      // Submit results back to gate
      gateClient.submitJobResults(
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setSetLogLevelResult(setLogLevelResult)
              .build());
    } catch (Exception e) {
      log.error("Error setting log level for job {}: {}", jobId, e.getMessage(), e);
      // Return empty result on error
      gateClient.submitJobResults(
          SubmitJobResultsRequest.newBuilder()
              .setJobId(jobId)
              .setEndOfTransmission(true)
              .setSetLogLevelResult(SubmitJobResultsRequest.SetLogLevelResult.newBuilder().build())
              .build());
    }
  }
}
