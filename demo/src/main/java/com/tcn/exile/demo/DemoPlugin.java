package com.tcn.exile.demo;

import ch.qos.logback.classic.LoggerContext;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;
import com.tcn.exile.plugin.PluginStatus;

import com.tcn.exile.memlogger.LogShipper;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.Entities.ExileAgentCall;
import tcnapi.exile.gate.v2.Entities.ExileAgentResponse;
import tcnapi.exile.gate.v2.Entities.ExileTelephonyResult;

import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.gateclients.v2.GateClient;
import jakarta.inject.Inject;
import io.micronaut.context.event.ApplicationEventListener;
import tcnapi.exile.gate.v2.Public;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;

@Singleton
public class DemoPlugin implements ApplicationEventListener<PluginConfigEvent>, PluginInterface, LogShipper {
  private static final Logger log = LoggerFactory.getLogger(DemoPlugin.class);
  private boolean running = false;

  @Inject
  GateClient gateClient;

  @Inject
  Environment environment;

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
        0,   // queueCompletedJobs
        0,   // queueActiveCount
        new HashMap<>(), // internalConfig
        new HashMap<>()  // internalStatus
    );
  }


  @Override
  public void listPools(String jobId, Public.StreamJobsResponse.ListPoolsRequest listPools) throws UnconfiguredException {
    log.info("Listing pools for job {}", jobId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setListPoolsResult(Public.SubmitJobResultsRequest.ListPoolsResult.newBuilder()
            .addPools(tcnapi.exile.core.v2.Entities.Pool.newBuilder()
                .setPoolId("A")
                .setDescription("Pool with id A")
                .setStatus(tcnapi.exile.core.v2.Entities.Pool.PoolStatus.READY)
                .build())
            .build())
        .build());

  }

  @Override
  public void getPoolStatus(String jobId, Public.StreamJobsResponse.GetPoolStatusRequest request) throws UnconfiguredException {
    log.info("Getting pool status for job={} and pool={}", jobId, request);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setGetPoolStatusResult(Public.SubmitJobResultsRequest.GetPoolStatusResult.newBuilder()
            .setPool(tcnapi.exile.core.v2.Entities.Pool.newBuilder()
                .setPoolId(request.getPoolId())
                .setStatus(tcnapi.exile.core.v2.Entities.Pool.PoolStatus.READY)
                .build())
            .build())
        .build());
  }

  @Override
  public void getPoolRecords(String jobId, Public.StreamJobsResponse.GetPoolRecordsRequest request) throws UnconfiguredException {
    log.info("Getting pool records for job {} and pool {}", jobId, request);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setGetPoolRecordsResult(Public.SubmitJobResultsRequest.GetPoolRecordsResult.newBuilder()
            .addRecords(tcnapi.exile.core.v2.Entities.Record.newBuilder()
                .setPoolId(request.getPoolId())
                .setRecordId("blue")
                .setJsonRecordPayload("{\"f1\": \"foo\"}")
                .build())
            .addRecords(tcnapi.exile.core.v2.Entities.Record.newBuilder()
                .setPoolId(request.getPoolId())
                .setRecordId("red")
                .setJsonRecordPayload("{\"f2\": \"bar\"}")
                .build())

            .build())
        .build());
  }


  @Override
  public void handleAgentCall(ExileAgentCall exileAgentCall) {
    log.info("Handling agent call for job {}", exileAgentCall);
  }

  @Override
  public void handleTelephonyResult(ExileTelephonyResult exileTelephonyResult) {
    log.info("Handling telephony result for job {}", exileTelephonyResult);
  }

  @Override
  public void handleAgentResponse(ExileAgentResponse exileAgentResponse) {
    log.info("Handling agent response for {}", exileAgentResponse);
  }

  @Override
  public void searchRecords(String jobId, Public.StreamJobsResponse.SearchRecordsRequest searchRecords) {

  }

  @Override
  public void readFields(String jobId, Public.StreamJobsResponse.GetRecordFieldsRequest getRecordFields) {
    log.info("Reading fields for job {} and record {}", jobId, getRecordFields.getRecordId());
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setGetRecordFieldsResult(Public.SubmitJobResultsRequest.GetRecordFieldsResult.newBuilder()
            .addFields(tcnapi.exile.core.v2.Entities.Field.newBuilder()
                .setFieldName("foo")
                .setFieldValue("bar")
                .setRecordId(getRecordFields.getRecordId())
                .build())
            .build())
        .build());
  }

  @Override
  public void writeFields(String jobId, Public.StreamJobsResponse.SetRecordFieldsRequest setRecordFields) {
    log.info("Writing fields for job {} and record {}", jobId, setRecordFields.getRecordId());
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setSetRecordFieldsResult(Public.SubmitJobResultsRequest.SetRecordFieldsResult.newBuilder().build())
        .build());
  }

  @Override
  public void createPayment(String jobId, Public.StreamJobsResponse.CreatePaymentRequest createPayment) {
    log.info("Creating payment for job {} and record {}", jobId, createPayment.getRecordId());
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setCreatePaymentResult(Public.SubmitJobResultsRequest.CreatePaymentResult.newBuilder().build())
        .build());
  }

  @Override
  public void popAccount(String jobId, Public.StreamJobsResponse.PopAccountRequest popAccount) {
    log.info("Popping account for job {} and record {}", jobId, popAccount.getRecordId());
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setPopAccountResult(Public.SubmitJobResultsRequest.PopAccountResult.newBuilder().build())
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
  public void info(String jobId, Public.StreamJobsResponse.InfoRequest info) {
    log.info("Info for job {}", jobId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setInfoResult(Public.SubmitJobResultsRequest.InfoResult.newBuilder()
            .setServerName(getServerName())
            .setCoreVersion(com.tcn.exile.gateclients.v2.BuildVersion.getBuildVersion())
            .setPluginName("DemoPlugin")
            .setPluginVersion(getVersion())
            .build())
        .build());
  }

  public Public.SubmitJobResultsRequest.InfoResult info() {
    return Public.SubmitJobResultsRequest.InfoResult.newBuilder()
        .setServerName(getServerName())
        .setCoreVersion(com.tcn.exile.gateclients.v2.BuildVersion.getBuildVersion())
        .setPluginName("DemoPlugin")
        .setPluginVersion(getVersion())
        .build();
  }

  @Override
  public void shutdown(String jobId, Public.StreamJobsResponse.SeppukuRequest shutdown) {

  }

  @Override
  public void logger(String jobId, Public.StreamJobsResponse.LoggingRequest logRequest) {
    log.debug("Received log request {} stream {} payload: {}", jobId, logRequest.getStreamLogs(), logRequest.getLoggerLevelsList());
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    for (var logger : logRequest.getLoggerLevelsList()) {
      log.debug("Setting logger {} to level {}", logger.getLoggerName(), logger.getLoggerLevel());
      var v = loggerContext.getLogger(logger.getLoggerName());
      if (v != null) {
        if (logger.getLoggerLevel() == Public.StreamJobsResponse.LoggingRequest.LoggerLevel.Level.DISABLED) {
          v.setLevel(ch.qos.logback.classic.Level.OFF);
        } else {
          v.setLevel(ch.qos.logback.classic.Level.toLevel(logger.getLoggerLevel().name()));
        }
      } else {
        log.warn("Logger {} not found", logger.getLoggerName());
      }
    }
    if (logRequest.getStreamLogs()) {
      MemoryAppenderInstance.getInstance().enableLogShipper(this);
    } else {
      MemoryAppenderInstance.getInstance().disableLogShipper();
    }

    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setLoggingResult(Public.SubmitJobResultsRequest.LoggingResult.newBuilder().build())
        .build());
  }

  @Override
  public void executeLogic(String jobId, Public.StreamJobsResponse.ExecuteLogicRequest executeLogic) {

  }

  @Override
  public void onApplicationEvent(PluginConfigEvent event) {
    if (event.isUnconfigured()) {
      log.info("Received unconfigured event");
      running = false;
    } else {
      log.info("Received configured event");
      running = true;
    }
  }

  @Override
  public void shipLogs(List<String> payload) {
    log.info("Ship logs");
    if (payload == null || payload.isEmpty()) {
      return;
    }
    String combinedPayload = String.join("\n", payload);
    gateClient.log(Public.LogRequest.newBuilder().setPayload(combinedPayload).build());
  }

  @Override
  public void stop() {
    log.info("Stopping shipping logs plugin");
    MemoryAppenderInstance.getInstance().disableLogShipper();

  }
}