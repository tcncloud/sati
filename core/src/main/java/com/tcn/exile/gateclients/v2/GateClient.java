/* 
 *  Copyright 2017-2024 original authors
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
 */
package com.tcn.exile.gateclients.v2;

import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.OrgInfo;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

public class GateClient extends GateClientAbstract {
    private static final Logger log = LoggerFactory.getLogger(GateClient.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public GateClient(Config currentConfig) {
        super(currentConfig);
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
            log.error("Failed to execute {} operation: {}", operationName, e.getMessage());
            throw new RuntimeException(e);
        } catch (StatusRuntimeException e) {
            if (handleStatusRuntimeException(e)) {
                log.warn("Connection issue during {} operation, channel reset: {}", operationName, e.getMessage());
                throw new RuntimeException("Connection issue during " + operationName + ", please retry", e);
            }
            log.error("gRPC error during {} operation: {} ({})", operationName, e.getMessage(), e.getStatus().getCode());
            throw new RuntimeException("Failed to execute " + operationName, e);
        } catch (Exception e) {
            log.error("Unexpected error during {} operation: {}", operationName, e.getMessage());
            throw new RuntimeException("Failed to execute " + operationName, e);
        }
    }

    @FunctionalInterface
    private interface GrpcOperation<T> {
        T execute(GateServiceGrpc.GateServiceBlockingStub client);
    }

    // Organization details retrieval
    public OrgInfo getOrganizationInfo() {
        return executeRequest("getOrganizationInfo", client -> {
            var result = client.getOrganizationInfo(
                Public.GetOrganizationInfoRequest.newBuilder().build()
            );
            return new OrgInfo(result.getOrgName(), result.getOrgName());
        });
    }

    // Job results submission (max 2MB)
    public Public.SubmitJobResultsResponse submitJobResults(Public.SubmitJobResultsRequest request) {
        log.info("GateClient submit job results request: {}", request.getJobId());
        try {
            return executeRequest("submitJobResults", client -> {
                var response = client.submitJobResults(request);
                if (response == null) {
                    throw new RuntimeException("Received null response from submitJobResults");
                }
                return response;
            });
        } catch (Exception e) {
            log.error("Failed to submit job results for job {}: {}", request.getJobId(), e.getMessage());
            throw new RuntimeException("Failed to submit job results", e);
        }
    }

    // Agent state management
    public Public.GetAgentStatusResponse getAgentStatus(Public.GetAgentStatusRequest request) {
        return executeRequest("getAgentStatus", client -> client.getAgentStatus(request));
    }

    public Public.UpdateAgentStatusResponse updateAgentStatus(Public.UpdateAgentStatusRequest request) {
        return executeRequest("updateAgentStatus", client -> client.updateAgentStatus(request));
    }

    public Iterator<Public.ListAgentsResponse> listAgents(Public.ListAgentsRequest request) {
        return executeRequest("listAgents", client -> client.listAgents(request));
    }

    public Public.UpsertAgentResponse upsertAgent(Public.UpsertAgentRequest request) {
        return executeRequest("upsertAgent", client -> client.upsertAgent(request));
    }

    public Public.GetAgentByIdResponse getAgentById(Public.GetAgentByIdRequest request) {
        return executeRequest("getAgentById", client -> client.getAgentById(request));
    }

    public Public.GetAgentByPartnerIdResponse getAgentByPartnerId(Public.GetAgentByPartnerIdRequest request) {
        return executeRequest("getAgentByPartnerId", client -> client.getAgentByPartnerId(request));
    }

    // Telephony operations
    public Public.DialResponse dial(Public.DialRequest request) {
        return executeRequest("dial", client -> client.dial(request));
    }

    // Recording controls
    public Public.StartCallRecordingResponse startCallRecording(Public.StartCallRecordingRequest request) {
        return executeRequest("startCallRecording", client -> client.startCallRecording(request));
    }

    public Public.StopCallRecordingResponse stopCallRecording(Public.StopCallRecordingRequest request) {
        return executeRequest("stopCallRecording", client -> client.stopCallRecording(request));
    }

    public Public.GetRecordingStatusResponse getRecordingStatus(Public.GetRecordingStatusRequest request) {
        return executeRequest("getRecordingStatus", client -> client.getRecordingStatus(request));
    }

    // Scrub list management
    public Public.ListScrubListsResponse listScrubLists(Public.ListScrubListsRequest request) {
        return executeRequest("listScrubLists", client -> client.listScrubLists(request));
    }

    public Public.AddScrubListEntriesResponse addScrubListEntries(Public.AddScrubListEntriesRequest request) {
        return executeRequest("addScrubListEntries", client -> client.addScrubListEntries(request));
    }

    public Public.UpdateScrubListEntryResponse updateScrubListEntry(Public.UpdateScrubListEntryRequest request) {
        return executeRequest("updateScrubListEntry", client -> client.updateScrubListEntry(request));
    }

    public Public.RemoveScrubListEntriesResponse removeScrubListEntries(Public.RemoveScrubListEntriesRequest request) {
        return executeRequest("removeScrubListEntries", client -> client.removeScrubListEntries(request));
    }

    public Public.LogResponse log(Public.LogRequest request) {
        return executeRequest("log", client -> client.log(request));
    }

    public Public.SaveAgentCallResponseResponse saveAgentCallResponse(Public.SaveAgentCallResponseRequest request) {
        return executeRequest("saveAgentCallResponse", client -> client.saveAgentCallResponse(request));
    }
}
