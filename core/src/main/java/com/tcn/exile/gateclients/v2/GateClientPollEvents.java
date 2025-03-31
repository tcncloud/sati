package com.tcn.exile.gateclients.v2;

import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
                log.debug("Configuration not set, skipping poll events");
                return;
            }
            if (!plugin.isRunning()) {
                log.debug("Plugin is not running (possibly due to database disconnection), skipping poll events");
                return;
            }
            var client = GateServiceGrpc.newBlockingStub(getChannel())
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .withWaitForReady();
            var response = client.pollEvents(PollEventsRequest.newBuilder().build());
            if (response.getEventsCount() == 0) {
                log.debug("Poll events request completed successfully but no events were received");
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
        } catch (StatusRuntimeException e) {
            if (handleStatusRuntimeException(e)) {
                // Already handled in parent class method
            } else {
                log.error("Error in poll events: {}", e.getMessage());
            }
        } catch (UnconfiguredException e) {
            log.error("Error while getting client configuration {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in poll events", e);
        }
    }
}
