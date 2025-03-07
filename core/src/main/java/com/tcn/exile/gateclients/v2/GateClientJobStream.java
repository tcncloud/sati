package com.tcn.exile.gateclients.v2;

import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.stub.StreamObserver;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.StreamJobsRequest;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;

@Singleton
public class GateClientJobStream extends GateClientAbstract
        implements StreamObserver<tcnapi.exile.gate.v2.Public.StreamJobsResponse> {
    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientJobStream.class);

    @Inject
    PluginInterface plugin;

    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        if (!plugin.isRunning()) {
            return;
        }
        log.debug("start()");
        try {
            if (!isRunning()) {
                shutdown();
                channel = this.getChannel();
                var client = GateServiceGrpc.newStub(channel)
                        .withDeadlineAfter(30, TimeUnit.SECONDS)
                        .withWaitForReady();

                client.streamJobs(StreamJobsRequest.newBuilder().build(), this);
            }
        } catch (UnconfiguredException e) {
            log.error("Error while starting job stream {}", e.getMessage());
        }
    }

    public boolean isRunning() {
        if (channel == null) {
            return false;
        }
        if (channel.isTerminated()) {
            return false;
        }
        if (channel.isShutdown()) {
            return false;
        }
        return true;
    }

    @Override
    public void onNext(StreamJobsResponse value) {
        log.debug("Received {} job", value.getJobId());
        try {
            if (value.hasListPools()) {
                plugin.listPools(value.getJobId());
            } else if (value.hasGetPoolStatus()) {
            } else if (value.hasGetPoolRecords()) {
            } else if (value.hasSearchRecords()) {
            } else if (value.hasGetRecordFields()) {
            } else if (value.hasSetRecordFields()) {
            } else if (value.hasCreatePayment()) {
            } else if (value.hasPopAccount()) {
            } else if (value.hasInfo()) {
            } else if (value.hasShutdown()) {
            } else if (value.hasLog()) {
            } else {
                log.error("Unknown job type {}", value.getUnknownFields());
            }

        } catch (UnconfiguredException e) {
            log.error("Error while handling job {}", value.getJobId());
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("Error while handling job stream", t);
        this.shutdown();
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        this.shutdown();
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onCompleted'");
    }

}
