package com.tcn.exile.gateclients.v2;

import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.StatusRuntimeException;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.GetClientConfigurationRequest;

@Singleton
public class GetClientConfiguration extends GateClientAbstract {
    PluginConfigEvent event = null;

    @Inject
    PluginInterface plugin;

    @Inject
    ApplicationEventPublisher<PluginConfigEvent> eventPublisher;

    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GetClientConfiguration.class);

    private void publishEvent(PluginConfigEvent event) {
        if (event != null) {
            log.debug("Publishing event {}", event);
            eventPublisher.publishEvent(event);
        }

    }

    
    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        try {
            var client = GateServiceGrpc.newBlockingStub(getChannel())
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .withWaitForReady();
            var response = client.getClientConfiguration(GetClientConfigurationRequest.newBuilder().build());
            var newEvent = new PluginConfigEvent(this)
                    .setConfigurationName(response.getConfigName())
                    .setConfigurationPayload(response.getConfigPayload())
                    .setOrgId(response.getOrgName())
                    .setOrgName(response.getOrgName())
                    .setUnconfigured(false);
            if (!newEvent.equals(event)) {
                log.debug("Received new configuration, we will emit an event");
                log.trace("{}", newEvent);
                event = newEvent;
                publishEvent(event);
            }
            return;
        } catch (UnconfiguredException e) {
            log.error("Error while getting client configuration {}", e.getMessage());
        } catch (StatusRuntimeException e) {
            log.error("Error while trying to connect {}", e.getMessage());
        }
        // we get here only if there is an error
        if (this.event != null) {
            log.debug("Due to the previous error, we will emit an unconfigured event");
            this.event.setUnconfigured(true);
            publishEvent(event);
        }
    }
}
