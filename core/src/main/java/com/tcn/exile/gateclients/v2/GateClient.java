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
import com.tcn.exile.log.LogCategory;
import com.tcn.exile.log.StructuredLogger;
import com.tcn.exile.models.OrgInfo;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

public class GateClient extends GateClientAbstract {
  private static final StructuredLogger log = new StructuredLogger(GateClient.class);
  private static final int DEFAULT_TIMEOUT_SECONDS = 300;

  public GateClient(String tenant, Config currentConfig) {
    super(tenant, currentConfig);
  }

  @Override
  public void start() {
    // this does not need any implementation
  }

  protected GateServiceGrpc.GateServiceBlockingStub getStub(ManagedChannel channel) {
    return GateServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withWaitForReady();
  }

  private <T> T executeRequest(String operationName, GrpcOperation<T> grpcOperation) {
    try {
      var result = grpcOperation.execute(getChannel());
      if (result == null) {
        throw new RuntimeException("Received null response from " + operationName);
      }
      return result;
    } catch (UnconfiguredException e) {
      log.error(
          LogCategory.GRPC,
          "OperationFailed",
          "Failed to execute %s operation: %s",
          operationName,
          e.getMessage());
      throw new RuntimeException(e);
    } catch (StatusRuntimeException e) {
      if (handleStatusRuntimeException(e)) {
        log.warn(
            LogCategory.GRPC,
            "ConnectionIssue",
            "Connection issue during %s operation, channel reset: %s",
            operationName,
            e.getMessage());
        throw new RuntimeException(
            "Connection issue during " + operationName + ", please retry", e);
      }
      log.error(
          LogCategory.GRPC,
          "GrpcError",
          "gRPC error during %s operation: %s (%s)",
          operationName,
          e.getMessage(),
          e.getStatus().getCode());
      throw new RuntimeException("Failed to execute " + operationName, e);
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "UnexpectedError",
          "Unexpected error during %s operation: %s",
          operationName,
          e.getMessage());
      throw new RuntimeException("Failed to execute " + operationName, e);
    }
  }

  @FunctionalInterface
  private interface GrpcOperation<T> {
    T execute(ManagedChannel channel);
  }

  // Organization details retrieval
  public OrgInfo getOrganizationInfo() {
    return executeRequest(
        "getOrganizationInfo",
        client -> {
          var result =
              getStub(client).getOrganizationInfo(GetOrganizationInfoRequest.newBuilder().build());
          return new OrgInfo(result.getOrgName(), result.getOrgName());
        });
  }

  // Job results submission (max 2MB)
  public SubmitJobResultsResponse submitJobResults(SubmitJobResultsRequest request) {
    log.info(
        LogCategory.GRPC,
        "SubmitJobResults",
        "GateClient submit job results request: %s",
        request.getJobId());
    try {
      return executeRequest(
          "submitJobResults",
          client -> {
            var response = getStub(client).submitJobResults(request);
            if (response == null) {
              throw new RuntimeException("Received null response from submitJobResults");
            }
            return response;
          });
    } catch (Exception e) {
      log.error(
          LogCategory.GRPC,
          "SubmitJobResultsFailed",
          "Failed to submit job results for job %s: %s",
          request.getJobId(),
          e.getMessage());
      throw new RuntimeException("Failed to submit job results", e);
    }
  }

  // Agent state management
  public GetAgentStatusResponse getAgentStatus(GetAgentStatusRequest request) {
    return executeRequest("getAgentStatus", client -> getStub(client).getAgentStatus(request));
  }

  public UpdateAgentStatusResponse updateAgentStatus(UpdateAgentStatusRequest request) {
    return executeRequest(
        "updateAgentStatus", client -> getStub(client).updateAgentStatus(request));
  }

  public Iterator<ListAgentsResponse> listAgents(ListAgentsRequest request) {
    return executeRequest("listAgents", client -> getStub(client).listAgents(request));
  }

  public UpsertAgentResponse upsertAgent(UpsertAgentRequest request) {
    return executeRequest("upsertAgent", client -> getStub(client).upsertAgent(request));
  }

  public GetAgentByIdResponse getAgentById(GetAgentByIdRequest request) {
    return executeRequest("getAgentById", client -> getStub(client).getAgentById(request));
  }

  public GetAgentByPartnerIdResponse getAgentByPartnerId(GetAgentByPartnerIdRequest request) {
    return executeRequest(
        "getAgentByPartnerId", client -> getStub(client).getAgentByPartnerId(request));
  }

  // Telephony operations
  public DialResponse dial(DialRequest request) {
    return executeRequest("dial", client -> getStub(client).dial(request));
  }

  public ListNCLRulesetNamesResponse listNCLRulesetNames(ListNCLRulesetNamesRequest request) {
    return executeRequest(
        "listNCLRulesetNames", client -> getStub(client).listNCLRulesetNames(request));
  }

  // Recording controls
  public StartCallRecordingResponse startCallRecording(StartCallRecordingRequest request) {
    return executeRequest(
        "startCallRecording", client -> getStub(client).startCallRecording(request));
  }

  public StopCallRecordingResponse stopCallRecording(StopCallRecordingRequest request) {
    return executeRequest(
        "stopCallRecording", client -> getStub(client).stopCallRecording(request));
  }

  public GetRecordingStatusResponse getRecordingStatus(GetRecordingStatusRequest request) {
    return executeRequest(
        "getRecordingStatus", client -> getStub(client).getRecordingStatus(request));
  }

  // Scrub list management
  public ListScrubListsResponse listScrubLists(ListScrubListsRequest request) {
    return executeRequest("listScrubLists", client -> getStub(client).listScrubLists(request));
  }

  public AddScrubListEntriesResponse addScrubListEntries(AddScrubListEntriesRequest request) {
    return executeRequest(
        "addScrubListEntries", client -> getStub(client).addScrubListEntries(request));
  }

  public UpdateScrubListEntryResponse updateScrubListEntry(UpdateScrubListEntryRequest request) {
    return executeRequest(
        "updateScrubListEntry", client -> getStub(client).updateScrubListEntry(request));
  }

  public RemoveScrubListEntriesResponse removeScrubListEntries(
      RemoveScrubListEntriesRequest request) {
    return executeRequest(
        "removeScrubListEntries", client -> getStub(client).removeScrubListEntries(request));
  }

  public LogResponse log(LogRequest request) {
    return executeRequest("log", client -> getStub(client).log(request));
  }

  public AddAgentCallResponseResponse addAgentCallResponse(AddAgentCallResponseRequest request) {
    return executeRequest(
        "addAgentCallResponse", client -> getStub(client).addAgentCallResponse(request));
  }

  public ListHuntGroupPauseCodesResponse listHuntGroupPauseCodes(
      ListHuntGroupPauseCodesRequest request) {
    return executeRequest(
        "listHuntGroupPauseCodes", client -> getStub(client).listHuntGroupPauseCodes(request));
  }

  public PutCallOnSimpleHoldResponse putCallOnSimpleHold(PutCallOnSimpleHoldRequest request) {
    return executeRequest(
        "putCallOnSimpleHold", client -> getStub(client).putCallOnSimpleHold(request));
  }

  public TakeCallOffSimpleHoldResponse takeCallOffSimpleHold(TakeCallOffSimpleHoldRequest request) {
    return executeRequest(
        "takeCallOffSimpleHold", client -> getStub(client).takeCallOffSimpleHold(request));
  }

  public RotateCertificateResponse rotateCertificate(RotateCertificateRequest request) {
    return executeRequest(
        "rotateCertificate", client -> getStub(client).rotateCertificate(request));
  }

  public Iterator<SearchVoiceRecordingsResponse> searchVoiceRecordings(
      SearchVoiceRecordingsRequest request) {
    return executeRequest(
        "searchVoiceRecordings", client -> getStub(client).searchVoiceRecordings(request));
  }

  public GetVoiceRecordingDownloadLinkResponse getVoiceRecordingDownloadLink(
      GetVoiceRecordingDownloadLinkRequest request) {
    return executeRequest(
        "getVoiceRecordingDownloadLink",
        client -> getStub(client).getVoiceRecordingDownloadLink(request));
  }

  public ListSearchableRecordingFieldsResponse listSearchableRecordingFields(
      ListSearchableRecordingFieldsRequest request) {
    return executeRequest(
        "listSearchableRecordingFields",
        client -> getStub(client).listSearchableRecordingFields(request));
  }

  public CreateRecordingLabelResponse createRecordingLabel(CreateRecordingLabelRequest request) {
    return executeRequest(
        "createRecordingLabel", client -> getStub(client).createRecordingLabel(request));
  }

  public ListSkillsResponse ListSkills(ListSkillsRequest request) {
    return executeRequest("listSkills", client -> getStub(client).listSkills(request));
  }

  // List all skills assigned to an agent, and their proficiency
  public ListAgentSkillsResponse ListAgentSkills(ListAgentSkillsRequest request) {
    return executeRequest("listAgentSkills", client -> getStub(client).listAgentSkills(request));
  }

  // Assign a skill to an agent
  public AssignAgentSkillResponse AssignAgentSkill(AssignAgentSkillRequest request) {
    return executeRequest("assignAgentSkill", client -> getStub(client).assignAgentSkill(request));
  }

  // Unassign a skill from an agent
  public UnassignAgentSkillResponse UnassignAgentSkill(UnassignAgentSkillRequest request) {
    return executeRequest(
        "unassignAgentSkill", client -> getStub(client).unassignAgentSkill(request));
  }

  public TransferResponse transfer(TransferRequest request) {
    return executeRequest("transfer", client -> getStub(client).transfer(request));
  }

  public AddRecordToJourneyBufferResponse addRecordToJourneyBuffer(
      AddRecordToJourneyBufferRequest request) {
    return executeRequest(
        "addRecordToJourneyBuffer", client -> getStub(client).addRecordToJourneyBuffer(request));
  }
}
