package com.tcn.exile.demo;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.LookupType;
import com.tcn.exile.plugin.PluginInterface;
import com.tcn.exile.plugin.PluginStatus;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.Entities;
import tcnapi.exile.gate.v2.Entities.ExileAgentCall;
import tcnapi.exile.gate.v2.Entities.ExileAgentResponse;
import tcnapi.exile.gate.v2.Entities.ExileTelephonyResult;

import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.gateclients.v2.GateClient;
import jakarta.inject.Inject;
import io.micronaut.context.event.ApplicationEventListener;
import tcnapi.exile.gate.v2.Public;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
  public void listPools(String jobId) throws UnconfiguredException {
    log.info("Listing pools for job {}", jobId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
        .setJobId(jobId)
        .setEndOfTransmission(true)
        .setPoolListResult(Public.SubmitJobResultsRequest.PoolListResult.newBuilder()
            .addPools(tcnapi.exile.core.v2.Entities.Pool.newBuilder()
                .setPoolId("A")
                .setDescription("Pool with id A")
                .setStatus(tcnapi.exile.core.v2.Entities.Pool.PoolStatus.READY)
                .build())
            .build())
        .build());

  }

  @Override
  public void getPoolStatus(String jobId, String satiPoolId) throws UnconfiguredException {
    log.info("Getting pool status for job={} and pool={}", jobId, satiPoolId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setPoolStatusResult(Public.SubmitJobResultsRequest.PoolStatusResult.newBuilder()
                    .setPool(tcnapi.exile.core.v2.Entities.Pool.newBuilder()
                            .setPoolId(satiPoolId)
                            .setStatus(tcnapi.exile.core.v2.Entities.Pool.PoolStatus.READY)
                            .build())
                    .build())
            .build());
  }

  @Override
  public void getPoolRecords(String jobId, String satiPoolId) throws UnconfiguredException {
    log.info("Getting pool records for job {} and pool {}", jobId, satiPoolId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
                    .setPoolRecordsResult(Public.SubmitJobResultsRequest.PoolRecordsResult.newBuilder()
                            .addRecords(tcnapi.exile.core.v2.Entities.Record.newBuilder()
                                    .setPoolId(satiPoolId)
                                    .setRecordId("blue")
                                    .setJsonRecordPayload("{\"f1\": \"foo\"}")
                                    .build())
                            .addRecords(tcnapi.exile.core.v2.Entities.Record.newBuilder()
                                    .setPoolId(satiPoolId)
                                    .setRecordId("red")
                                    .setJsonRecordPayload("{\"f2\": \"bar\"}")
                                    .build())

                            .build())
            .build());
  }

  @Override
  public void searchRecords(String jobId, LookupType lookupType, String lookupValue, @Nullable String satiParentId) throws UnconfiguredException {
    log.info("Searching records for job {} with type {} and value {}", jobId, lookupType, lookupValue);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setRecordSearchResult(Public.SubmitJobResultsRequest.RecordSearchResult.newBuilder()
                    .addRecords(tcnapi.exile.core.v2.Entities.Record.newBuilder()
                            .setRecordId("blue")
                            .setJsonRecordPayload("{\"foo\": \"bar\"}")
                            .build())
                    .build())
            .build());
  }

  @Override
  public void readFields(String jobId, String recordId, String[] fields) throws UnconfiguredException {
    log.info("Reading {} fields for job {} and record {}", fields.length, jobId, recordId);

    String[] values = {"foo", "bar", "baz"};
    var res = Public.SubmitJobResultsRequest.RecordFieldsResult.newBuilder();
    var fieldsList =  new ArrayList<tcnapi.exile.core.v2.Entities.Field>();

    for (int i = 0; i < fields.length; i++){
      fieldsList.add(tcnapi.exile.core.v2.Entities.Field.newBuilder()
              .setFieldName(fields[i])
              .setFieldValue(values[i%3])
              .setRecordId(recordId)
              .build());
    }
    log.info("sending fields[{}] {}",fieldsList.size(), fieldsList);

    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setRecordFieldsResult(Public.SubmitJobResultsRequest.RecordFieldsResult.newBuilder()
                    .addAllFields(fieldsList)
                    .build())
            .build());
  }

  @Override
  public void writeFields(String jobId, String recordId, Map<String, String> fields) throws UnconfiguredException {
    log.info("Writing fields for job {} and record {} fields {}", jobId, recordId, fields);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setRecordFieldsResult(Public.SubmitJobResultsRequest.RecordFieldsResult.newBuilder().build())
            .build());
  }

  @Override
  public void createPayment(String jobId, String recordId, Map<String, String> fields) throws UnconfiguredException {
    log.info("Creating payment for job {} and record {}, fields {}", jobId, recordId, fields);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setPaymentCreationResult(Public.SubmitJobResultsRequest.PaymentCreationResult.newBuilder().build())
            .build());
  }

  @Override
  public void popAccount(String jobId, String recordId, String partnerUserId, String callId, String callType) throws UnconfiguredException {
    log.info("Popping account for job {} and record {}", jobId, recordId);
    gateClient.submitJobResults(Public.SubmitJobResultsRequest.newBuilder()
            .setJobId(jobId)
            .setEndOfTransmission(true)
            .setAccountPopResult(Public.SubmitJobResultsRequest.AccountPopResult.newBuilder().build())
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