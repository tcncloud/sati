package com.tcn.exile.gateclients.v2;

import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.GetClientConfigurationRequest;

@Singleton
public class GetClientConfiguration extends GateClientAbstract {

    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GetClientConfiguration.class);

    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        log.debug("getting client configuration");
        try {
            var client = GateServiceGrpc.newBlockingStub(getChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .withWaitForReady();
            var response = client.getClientConfiguration(GetClientConfigurationRequest.newBuilder().build());
            log.debug("Received client configuration: {}", response);
        } catch (UnconfiguredException e) {
            log.error("Error while getting client configuration", e);
        }
    }
}
