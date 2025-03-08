package com.tcn.exile.gateclients.v2;

import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.stub.StreamObserver;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.PollEventsRequest;

@Singleton
public class GateClientEventStream extends GateClientAbstract {
    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientEventStream.class);

    @Inject
    PluginInterface plugin;

    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        try {
            if (!isUnconfigured()) {
                log.debug("pollEvents().start()");
                var client = GateServiceGrpc.newBlockingStub(getChannel())
                        .withDeadlineAfter(30, TimeUnit.SECONDS)
                        .withWaitForReady();
                var response = client.pollEvents(PollEventsRequest.newBuilder().build());
                response.getEventsList().forEach(event -> {
                    log.debug("Received event {}", event);
                });
            }
        } catch (UnconfiguredException e) {
            log.error("Error while getting client configuration {}", e.getMessage());
        }
    }

}
