package com.tcn.exile.gateclients.v2;

import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.gateclients.UnconfiguredException;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.micronaut.context.event.ApplicationEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public abstract class GateClientAbstract implements ApplicationEventListener<ConfigEvent> {
    private static final Logger log = LoggerFactory.getLogger(com.tcn.exile.gateclients.v2.GateClientAbstract.class);

    protected ManagedChannel channel;
    private ConfigEvent event;

    @Override
    public boolean supports(ConfigEvent event) {
        return ApplicationEventListener.super.supports(event);
    }

    @Override
    public void onApplicationEvent(ConfigEvent event) {
        log.debug("Received ConfigEvent {}", event);
        if (event != null) {
            shutdown();
            this.event = event;
            // start(); <- this is b/c all of the 3 beans have the start() method invoked thru @Scheduled annotation
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
                    log.error("Can't shutdown the channel", ex);
                }
            }
        }
    }
    /**
     * Gets or creates a gRPC channel for communication with the Gate service.
     * @return A ManagedChannel instance.
     * @throws UnconfiguredException if the client is not configured.
     */
    public ManagedChannel getChannel() throws UnconfiguredException {
        if ((channel != null) && !channel.isShutdown() && !channel.isTerminated()) {
            return channel;
        }
        shutdown();
        try {
            log.debug("creating a new channel");
            var channelCredentials = TlsChannelCredentials.newBuilder()
                .trustManager(new ByteArrayInputStream(getConfig().getRootCert().getBytes()))
                .keyManager(
                    new ByteArrayInputStream(getConfig().getPublicCert().getBytes()),
                    new ByteArrayInputStream(getConfig().getPrivateKey().getBytes()))
                .build();
            channel = Grpc.newChannelBuilderForAddress(
                    getConfig().getApiHostname(), getConfig().getApiPort(), channelCredentials)
                .keepAliveTime(1, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .idleTimeout(30, TimeUnit.MINUTES)
                .overrideAuthority("exile-proxy")
                // TODO: add service configuration for retry
//          .defaultServiceConfig(null)
                .build();
                log.debug("channel: {}", channel);
            return channel;
        } catch (IOException e) {
            throw new UnconfiguredException("TCN Gate client is unconfigured", e);
        }
    }

    /**
     * Retrieves the current configuration.
     * @return The current ConfigEvent.
     * @throws UnconfiguredException if the client is not configured.
     */
    protected ConfigEvent getConfig() throws UnconfiguredException {
        if ((event == null) || event.isUnconfigured()) {
            throw new UnconfiguredException("TCN Gate client is unconfigured");
        }
        return event;
    }

    public abstract void start();


    // Initial configuration retrieval for client setup
    // public abstract ClientConfigResponse getClientConfiguration(ClientConfigRequest request);

    // Organization details retrieval
    // public abstract OrganizationResponse getOrganizationInfo(OrganizationRequest request);

    // Periodic event polling (up to 4MB)
    // public abstract EventPollResponse pollEvents(EventPollRequest request);

    // Job streaming connection
    // public abstract StreamJobsResponse streamJobs(StreamJobsRequest request);

    // Job results submission (max 2MB)
    // public abstract JobResultsResponse submitJobResults(JobResultsRequest request);

    // Agent state management
    // public abstract AgentStatusResponse getAgentStatus(AgentStatusRequest request);
    // public abstract AgentStatusUpdateResponse updateAgentStatus(AgentStatusUpdateRequest request);
    // public abstract AgentListResponse listAgents(AgentListRequest request);
    // public abstract AgentUpsertResponse upsertAgent(AgentUpsertRequest request);
    // public abstract AgentResponse getAgentById(AgentRequest request);
    // public abstract AgentByPartnerIdResponse getAgentByPartnerId(AgentByPartnerIdRequest request);

    // Telephony operations
    // public abstract CallResponse dial(CallRequest request);

    // Recording controls
    // public abstract RecordingStartResponse startCallRecording(RecordingStartRequest request);
    // public abstract RecordingStopResponse stopCallRecording(RecordingStopRequest request);
    // public abstract RecordingStatusResponse getRecordingStatus(RecordingStatusRequest request);

    // Scrub list management
    // public abstract ScrubListResponse listScrubLists(ScrubListRequest request);
    // public abstract Public.ScrubListEntriesAddRequest addScrubListEntries(Public.ScrubListEntriesAddRequest request);
    // public abstract Public.ScrubListEntryUpdateResponse updateScrubListEntry(Public.ScrubListEntryUpdateRequest request);
    // public abstract Public.ScrubListEntriesRemoveResponse removeScrubListEntries(Public.ScrubListEntriesRemoveRequest request);
}
