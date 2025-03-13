package com.tcn.exile.demo;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;
import com.tcn.exile.plugin.PluginStatus;

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

import java.util.HashMap;

@Singleton
public class DemoPlugin implements ApplicationEventListener<PluginConfigEvent>, PluginInterface {
  private static final Logger log = LoggerFactory.getLogger(DemoPlugin.class);
  private boolean running = false;

  @Inject
  GateClient gateClient;


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
        .setListPoolResult(Public.SubmitJobResultsRequest.ListPoolsResult.newBuilder()
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

  @Override
  public void info(String jobId, Public.StreamJobsResponse.InfoRequest info) {
    log.info("Info for job {}", jobId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setInfoResult(Public.SubmitJobResultsRequest.InfoResult.newBuilder().build())
        .build());

  }

  @Override
  public void shutdown(String jobId, Public.StreamJobsResponse.SeppukuRequest shutdown) {

  }

  @Override
  public void log(String jobId, Public.StreamJobsResponse.LogRequest log) {

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
} 