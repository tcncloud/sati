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

import build.buf.gen.tcnapi.exile.gate.v2.*;
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.OrgInfo;
import io.grpc.StatusRuntimeException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GateClient extends GateClientAbstract {
  private static final Logger log = LoggerFactory.getLogger(GateClient.class);
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  public GateClient(String tenant, Config currentConfig) {
    super(tenant, currentConfig);
  }

  @Override
  public void start() {
    // this does not need any implementation
  }

  protected GateServiceGrpc.GateServiceBlockingStub getStub() throws UnconfiguredException {
    return GateServiceGrpc.newBlockingStub(getChannel())
        .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withWaitForReady();
  }

  private <T> T executeRequest(String operationName, GrpcOperation<T> grpcOperation) {
    try {
      var client = getStub();
      var result = grpcOperation.execute(client);
      if (result == null) {
        throw new RuntimeException("Received null response from " + operationName);
      }
      return result;
    } catch (UnconfiguredException e) {
      log.error(
          "Tenant: {} - Failed to execute {} operation: {}", tenant, operationName, e.getMessage());
      throw new RuntimeException(e);
    } catch (StatusRuntimeException e) {
      if (handleStatusRuntimeException(e)) {
        log.warn(
            "Tenant: {} - Connection issue during {} operation, channel reset: {}",
            tenant,
            operationName,
            e.getMessage());
        throw new RuntimeException(
            "Connection issue during " + operationName + ", please retry", e);
      }
      log.error(
          "Tenant: {} - gRPC error during {} operation: {} ({})",
          tenant,
          operationName,
          e.getMessage(),
          e.getStatus().getCode());
      throw new RuntimeException("Failed to execute " + operationName, e);
    } catch (Exception e) {
      log.error(
          "Tenant: {} - Unexpected error during {} operation: {}",
          tenant,
          operationName,
          e.getMessage());
      throw new RuntimeException("Failed to execute " + operationName, e);
    }
  }

  @FunctionalInterface
  private interface GrpcOperation<T> {
    T execute(GateServiceGrpc.GateServiceBlockingStub client);
  }

  // Organization details retrieval
  public OrgInfo getOrganizationInfo() {
    return executeRequest(
        "getOrganizationInfo",
        client -> {
          var result = client.getOrganizationInfo(GetOrganizationInfoRequest.newBuilder().build());
          return new OrgInfo(result.getOrgName(), result.getOrgName());
        });
  }

  // Job results submission (max 2MB)
  public SubmitJobResultsResponse submitJobResults(SubmitJobResultsRequest request) {
    log.info("Tenant: {} - GateClient submit job results request: {}", tenant, request.getJobId());
    try {
      return executeRequest(
          "submitJobResults",
          client -> {
            var response = client.submitJobResults(request);
            if (response == null) {
              throw new RuntimeException("Received null response from submitJobResults");
            }
            return response;
          });
    } catch (Exception e) {
      log.error(
          "Tenant: {} - Failed to submit job results for job {}: {}",
          tenant,
          request.getJobId(),
          e.getMessage());
      throw new RuntimeException("Failed to submit job results", e);
    }
  }

  // Agent state management
  public GetAgentStatusResponse getAgentStatus(GetAgentStatusRequest request) {
    return executeRequest("getAgentStatus", client -> client.getAgentStatus(request));
  }

  public UpdateAgentStatusResponse updateAgentStatus(UpdateAgentStatusRequest request) {
    return executeRequest("updateAgentStatus", client -> client.updateAgentStatus(request));
  }

  public Iterator<ListAgentsResponse> listAgents(ListAgentsRequest request) {
    return executeRequest("listAgents", client -> client.listAgents(request));
  }

  public UpsertAgentResponse upsertAgent(UpsertAgentRequest request) {
    return executeRequest("upsertAgent", client -> client.upsertAgent(request));
  }

  public GetAgentByIdResponse getAgentById(GetAgentByIdRequest request) {
    return executeRequest("getAgentById", client -> client.getAgentById(request));
  }

  public GetAgentByPartnerIdResponse getAgentByPartnerId(GetAgentByPartnerIdRequest request) {
    return executeRequest("getAgentByPartnerId", client -> client.getAgentByPartnerId(request));
  }

  // Telephony operations
  public DialResponse dial(DialRequest request) {
    return executeRequest("dial", client -> client.dial(request));
  }

  // Recording controls
  public StartCallRecordingResponse startCallRecording(StartCallRecordingRequest request) {
    return executeRequest("startCallRecording", client -> client.startCallRecording(request));
  }

  public StopCallRecordingResponse stopCallRecording(StopCallRecordingRequest request) {
    return executeRequest("stopCallRecording", client -> client.stopCallRecording(request));
  }

  public GetRecordingStatusResponse getRecordingStatus(GetRecordingStatusRequest request) {
    return executeRequest("getRecordingStatus", client -> client.getRecordingStatus(request));
  }

  // Scrub list management
  public ListScrubListsResponse listScrubLists(ListScrubListsRequest request) {
    return executeRequest("listScrubLists", client -> client.listScrubLists(request));
  }

  public AddScrubListEntriesResponse addScrubListEntries(AddScrubListEntriesRequest request) {
    return executeRequest("addScrubListEntries", client -> client.addScrubListEntries(request));
  }

  public UpdateScrubListEntryResponse updateScrubListEntry(UpdateScrubListEntryRequest request) {
    return executeRequest("updateScrubListEntry", client -> client.updateScrubListEntry(request));
  }

  public RemoveScrubListEntriesResponse removeScrubListEntries(
      RemoveScrubListEntriesRequest request) {
    return executeRequest(
        "removeScrubListEntries", client -> client.removeScrubListEntries(request));
  }

  public LogResponse log(LogRequest request) {
    return executeRequest("log", client -> client.log(request));
  }

  public AddAgentCallResponseResponse addAgentCallResponse(AddAgentCallResponseRequest request) {
    return executeRequest("addAgentCallResponse", client -> client.addAgentCallResponse(request));
  }

  public ListHuntGroupPauseCodesResponse listHuntGroupPauseCodes(
      ListHuntGroupPauseCodesRequest request) {
    return executeRequest(
        "listHuntGroupPauseCodes", client -> client.listHuntGroupPauseCodes(request));
  }

  public PutCallOnSimpleHoldResponse putCallOnSimpleHold(PutCallOnSimpleHoldRequest request) {
    return executeRequest("putCallOnSimpleHold", client -> client.putCallOnSimpleHold(request));
  }

  public TakeCallOffSimpleHoldResponse takeCallOffSimpleHold(TakeCallOffSimpleHoldRequest request) {
    return executeRequest("takeCallOffSimpleHold", client -> client.takeCallOffSimpleHold(request));
  }

  public RotateCertificateResponse rotateCertificate(RotateCertificateRequest request) {
    return executeRequest("rotateCertificate", client -> client.rotateCertificate(request));
  }

  public Iterator<SearchVoiceRecordingsResponse> searchVoiceRecordings(
      SearchVoiceRecordingsRequest request) {
    return executeRequest("searchVoiceRecordings", client -> client.searchVoiceRecordings(request));
  }

  public GetVoiceRecordingDownloadLinkResponse getVoiceRecordingDownloadLink(
      GetVoiceRecordingDownloadLinkRequest request) {
    return executeRequest(
        "getVoiceRecordingDownloadLink", client -> client.getVoiceRecordingDownloadLink(request));
  }

  public ListSearchableRecordingFieldsResponse listSearchableRecordingFields(
      ListSearchableRecordingFieldsRequest request) {
    return executeRequest(
        "listSearchableRecordingFields", client -> client.listSearchableRecordingFields(request));
  }

  public ListSkillsResponse ListSkills(ListSkillsRequest request) {
    return executeRequest("listSkills", client -> client.listSkills(request));
  }

  // List all skills assigned to an agent, and their proficiency
  public ListAgentSkillsResponse ListAgentSkills(ListAgentSkillsRequest request) {
    return executeRequest("listAgentSkills", client -> client.listAgentSkills(request));
  }

  // Assign a skill to an agent
  public AssignAgentSkillResponse AssignAgentSkill(AssignAgentSkillRequest request) {
    return executeRequest("assignAgentSkill", client -> client.assignAgentSkill(request));
  }

  // Unassign a skill from an agent
  public UnassignAgentSkillResponse UnassignAgentSkill(UnassignAgentSkillRequest request) {
    return executeRequest("unassignAgentSkill", client -> client.unassignAgentSkill(request));
  }
}
