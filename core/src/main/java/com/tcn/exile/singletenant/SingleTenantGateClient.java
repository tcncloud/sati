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
package com.tcn.exile.singletenant;

import com.tcn.exile.gateclients.v2.GateClient;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.OrgInfo;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public;

import java.util.Iterator;

@Singleton
@Requires(property = "sati.tenant.type", value = "single")
public class SingleTenantGateClient extends GateClient {
    private static final Logger log = LoggerFactory.getLogger(SingleTenantGateClient.class);

    @Override
    protected GateServiceGrpc.GateServiceBlockingStub getStub() throws UnconfiguredException {
        return super.getStub();
    }

    @Override
    public OrgInfo getOrganizationInfo() {
        return super.getOrganizationInfo();
    }

    @Override
    public Public.SubmitJobResultsResponse submitJobResults(Public.SubmitJobResultsRequest request) {
        return super.submitJobResults(request);
    }

    @Override
    public Public.GetAgentStatusResponse getAgentStatus(Public.GetAgentStatusRequest request) {
        return super.getAgentStatus(request);
    }

    @Override
    public Public.UpdateAgentStatusResponse updateAgentStatus(Public.UpdateAgentStatusRequest request) {
        return super.updateAgentStatus(request);
    }

    @Override
    public Iterator<Public.ListAgentsResponse> listAgents(Public.ListAgentsRequest request) {
        return super.listAgents(request);
    }

    @Override
    public Public.UpsertAgentResponse upsertAgent(Public.UpsertAgentRequest request) {
        return super.upsertAgent(request);
    }

    @Override
    public Public.GetAgentByIdResponse getAgentById(Public.GetAgentByIdRequest request) {
        return super.getAgentById(request);
    }

    @Override
    public Public.GetAgentByPartnerIdResponse getAgentByPartnerId(Public.GetAgentByPartnerIdRequest request) {
        return super.getAgentByPartnerId(request);
    }

    @Override
    public Public.DialResponse dial(Public.DialRequest request) {
        return super.dial(request);
    }

    @Override
    public Public.StartCallRecordingResponse startCallRecording(Public.StartCallRecordingRequest request) {
        return super.startCallRecording(request);
    }

    @Override
    public Public.StopCallRecordingResponse stopCallRecording(Public.StopCallRecordingRequest request) {
        return super.stopCallRecording(request);
    }

    @Override
    public Public.GetRecordingStatusResponse getRecordingStatus(Public.GetRecordingStatusRequest request) {
        return super.getRecordingStatus(request);
    }

    @Override
    public Public.ListScrubListsResponse listScrubLists(Public.ListScrubListsRequest request) {
        return super.listScrubLists(request);
    }

    @Override
    public Public.AddScrubListEntriesResponse addScrubListEntries(Public.AddScrubListEntriesRequest request) {
        return super.addScrubListEntries(request);
    }

    @Override
    public Public.UpdateScrubListEntryResponse updateScrubListEntry(Public.UpdateScrubListEntryRequest request) {
        return super.updateScrubListEntry(request);
    }

    @Override
    public Public.RemoveScrubListEntriesResponse removeScrubListEntries(Public.RemoveScrubListEntriesRequest request) {
        return super.removeScrubListEntries(request);
    }

    @Override
    public Public.LogResponse log(Public.LogRequest request) {
        return super.log(request);
    }

    @Override
    public Public.SaveAgentCallResponseResponse saveAgentCallResponse(Public.SaveAgentCallResponseRequest request) {
        return super.saveAgentCallResponse(request);
    }
} 