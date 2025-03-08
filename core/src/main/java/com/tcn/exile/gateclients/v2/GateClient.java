package com.tcn.exile.gateclients.v2;

import com.tcn.exile.models.OrgInfo;

import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.Public;

@Singleton
public class GateClient extends GateClientAbstract{

    @Override
    public void start() {
        // this does not need any implementation
    }


    // Organization details retrieval
    public  OrgInfo getOrganizationInfo() {
        throw new UnsupportedOperationException("Unimplemented method 'getOrganizationInfo'");

    }

    // Job results submission (max 2MB)
    public  Public.SubmitJobResultsResponse submitJobResults(Public.SubmitJobResultsRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'submitJobResults'");
    }

    // Agent state management
    public  Public.GetAgentStatusResponse getAgentStatus(Public.GetAgentStatusResponse request) {
        throw new UnsupportedOperationException("Unimplemented method 'getAgentStatus'");
    }
    public  Public.UpdateAgentStatusResponse updateAgentStatus(Public.UpdateAgentStatusRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'updateAgentStatus'");
    }

    public  Public.ListAgentsResponse listAgents(Public.ListAgentsRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'listAgents'");
    }
    public  Public.UpsertAgentResponse upsertAgent(Public.UpsertAgentRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'upsertAgent'");
    }
    public  Public.GetAgentByIdResponse getAgentById(Public.GetAgentByIdRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'getAgentById'");
    }
    public  Public.GetAgentByPartnerIdResponse getAgentByPartnerId(Public.GetAgentByPartnerIdRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'getAgentByPartnerId'");
    }

    // Telephony operations
    public  Public.DialResponse dial(Public.DialRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'dial'");
    }

    // Recording controls
    public  Public.StartCallRecordingResponse startCallRecording(Public.StartCallRecordingRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'startCallRecording'");
    }
    public  Public.StopCallRecordingResponse stopCallRecording(Public.StopCallRecordingRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'stopCallRecording'");
    }
    public  Public.GetRecordingStatusResponse getRecordingStatus(Public.GetRecordingStatusRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'getRecordingStatus'");
    }

    // Scrub list management
    public  Public.ListScrubListsResponse listScrubLists(Public.ListScrubListsRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'listScrubLists'");
    }
    public  Public.AddScrubListEntriesResponse addScrubListEntries(Public.AddScrubListEntriesRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'addScrubListEntries'");
    }
    public  Public.UpdateAgentStatusResponse updateScrubListEntry(Public.UpdateAgentStatusRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'updateScrubListEntry'");
    }
    public  Public.RemoveScrubListEntriesResponse removeScrubListEntries(Public.RemoveScrubListEntriesRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'removeScrubListEntries'");
    }
}
    

