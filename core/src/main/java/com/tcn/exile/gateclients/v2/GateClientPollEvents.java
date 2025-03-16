package com.tcn.exile.gateclients.v2;

import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.PollEventsRequest;

@Singleton
public class GateClientPollEvents extends GateClientAbstract {
    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientPollEvents.class);

    @Inject
    PluginInterface plugin;

    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        try {
            if (isUnconfigured()) {
                log.trace("The configuration was not set, we will not start the job stream");
                return;
            }
            if (!plugin.isRunning()) {
                log.trace("The plugin is not running, we will not start the job stream");
                return;
            }
            var client = GateServiceGrpc.newBlockingStub(getChannel())
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .withWaitForReady();
            var response = client.pollEvents(PollEventsRequest.newBuilder().build());
            if (response.getEventsCount() == 0) {
                log.debug("No events received");
                return;
            }
            response.getEventsList().forEach(event -> {
                if (event.hasAgentCall()) {
                    log.debug("Received agent call event {} - {}", event.getAgentCall().getCallSid(), event.getAgentCall().getCallType());
                    plugin.handleAgentCall(event.getAgentCall());
                }
                if (event.hasAgentResponse()) {
                    log.debug("Received agent response event {}", event.getAgentResponse().getAgentCallResponseSid());
                    plugin.handleAgentResponse(event.getAgentResponse());
                }

                if (event.hasTelephonyResult()) {
                    log.debug("Received telephony result event {} - {}", event.getTelephonyResult().getCallSid(), event.getTelephonyResult().getCallType());
                    plugin.handleTelephonyResult(event.getTelephonyResult());
                }
            });
        } catch (UnconfiguredException e) {
            log.error("Error while getting client configuration {}", e.getMessage());
        }
    }

}
