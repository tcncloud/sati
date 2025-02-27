package com.tcn.exile.gateclients.v2;

import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.models.AgentUpsertRequest;

import io.grpc.ManagedChannel;
import io.micronaut.context.event.ApplicationEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.*;
import tcnapi.exile.gate.v2.Public.AgentByPartnerIdRequest;
import tcnapi.exile.gate.v2.Public.AgentByPartnerIdResponse;
import tcnapi.exile.gate.v2.Public.AgentListRequest;
import tcnapi.exile.gate.v2.Public.AgentListResponse;
import tcnapi.exile.gate.v2.Public.AgentRequest;
import tcnapi.exile.gate.v2.Public.AgentResponse;
import tcnapi.exile.gate.v2.Public.AgentStatusRequest;
import tcnapi.exile.gate.v2.Public.AgentStatusResponse;
import tcnapi.exile.gate.v2.Public.AgentStatusUpdateRequest;
import tcnapi.exile.gate.v2.Public.AgentStatusUpdateResponse;
import tcnapi.exile.gate.v2.Public.AgentUpsertResponse;
import tcnapi.exile.gate.v2.Public.CallRequest;
import tcnapi.exile.gate.v2.Public.CallResponse;
import tcnapi.exile.gate.v2.Public.ClientConfigRequest;
import tcnapi.exile.gate.v2.Public.ClientConfigResponse;
import tcnapi.exile.gate.v2.Public.EventPollRequest;
import tcnapi.exile.gate.v2.Public.EventPollResponse;
import tcnapi.exile.gate.v2.Public.JobResultsRequest;
import tcnapi.exile.gate.v2.Public.JobResultsResponse;
import tcnapi.exile.gate.v2.Public.OrganizationRequest;
import tcnapi.exile.gate.v2.Public.OrganizationResponse;
import tcnapi.exile.gate.v2.Public.RecordingStartRequest;
import tcnapi.exile.gate.v2.Public.RecordingStartResponse;
import tcnapi.exile.gate.v2.Public.RecordingStatusRequest;
import tcnapi.exile.gate.v2.Public.RecordingStatusResponse;
import tcnapi.exile.gate.v2.Public.RecordingStopRequest;
import tcnapi.exile.gate.v2.Public.RecordingStopResponse;
import tcnapi.exile.gate.v2.Public.ScrubListRequest;
import tcnapi.exile.gate.v2.Public.ScrubListResponse;
import tcnapi.exile.gate.v2.Public.StreamJobsRequest;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;

import java.util.concurrent.TimeUnit;

public abstract class GateClientAbstract implements ApplicationEventListener<ConfigEvent> {
    private static final Logger log = LoggerFactory.getLogger(com.tcn.exile.gateclients.GateClientAbstract.class);

    protected ManagedChannel channel;
    @Override
    public boolean supports(ConfigEvent event) {
        return ApplicationEventListener.super.supports(event);
    }

    @Override
    public void onApplicationEvent(ConfigEvent event) {
        if (event != null) {
            shutdown();
            start();
        }
    }

    protected void shutdown() {
        if ((this.channel != null) && (!this.channel.isShutdown() && !this.channel.isTerminated())) {
            channel.shutdown();
            try {
                channel.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
                try {
                    channel.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    log.error("Can't shutdown the channel", ex)
                }
            }
        }
    }

    public abstract void start();


    // Initial configuration retrieval for client setup
    public abstract ClientConfigResponse getClientConfiguration(ClientConfigRequest request);

    // Organization details retrieval
    public abstract OrganizationResponse getOrganizationInfo(OrganizationRequest request);

    // Periodic event polling (up to 4MB)
    public abstract EventPollResponse pollEvents(EventPollRequest request);

    // Job streaming connection
    public abstract StreamJobsResponse streamJobs(StreamJobsRequest request);

    // Job results submission (max 2MB)
    public abstract JobResultsResponse submitJobResults(JobResultsRequest request);

    // Agent state management
    public abstract AgentStatusResponse getAgentStatus(AgentStatusRequest request);
    public abstract AgentStatusUpdateResponse updateAgentStatus(AgentStatusUpdateRequest request);
    public abstract AgentListResponse listAgents(AgentListRequest request);
    public abstract AgentUpsertResponse upsertAgent(AgentUpsertRequest request);
    public abstract AgentResponse getAgentById(AgentRequest request);
    public abstract AgentByPartnerIdResponse getAgentByPartnerId(AgentByPartnerIdRequest request);

    // Telephony operations
    public abstract CallResponse dial(CallRequest request);

    // Recording controls
    public abstract RecordingStartResponse startCallRecording(RecordingStartRequest request);
    public abstract RecordingStopResponse stopCallRecording(RecordingStopRequest request);
    public abstract RecordingStatusResponse getRecordingStatus(RecordingStatusRequest request);

    // Scrub list management
    public abstract ScrubListResponse listScrubLists(ScrubListRequest request);
    public abstract Public.ScrubListEntriesAddRequest addScrubListEntries(Public.ScrubListEntriesAddRequest request);
    public abstract Public.ScrubListEntryUpdateResponse updateScrubListEntry(Public.ScrubListEntryUpdateRequest request);
    public abstract Public.ScrubListEntriesRemoveResponse removeScrubListEntries(Public.ScrubListEntriesRemoveRequest request);
}
