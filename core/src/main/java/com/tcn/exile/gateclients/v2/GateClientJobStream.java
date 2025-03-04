package com.tcn.exile.gateclients.v2;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.StreamJobsRequest;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;

public class GateClientJobStream extends GateClientAbstract
        implements StreamObserver<tcnapi.exile.gate.v2.Public.StreamJobsResponse> {
    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientJobStream.class);

    @Inject
    PluginInterface plugin;

    @Override
    public void start() {
        try {
            if (!isRunning()) {
                shutdown();
                channel = this.getChannel();
                var client = GateServiceGrpc.newStub(channel).withWaitForReady();
                client.streamJobs(StreamJobsRequest.newBuilder().build(), this);
            }
        } catch (UnconfiguredException e) {
            log.error("Error while starting job stream", e);
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
            if (value.hasJob()) {
                if (value.getJob().hasListPools()) {
                    plugin.listPools(value.getJobId());
                } else if (value.getJob().hasGetPoolStatus()) {
                } else if (value.getJob().hasGetPoolRecords()) {
                } else if (value.getJob().hasSearchRecords()) {
                } else if (value.getJob().hasGetRecordFields()) {
                } else if (value.getJob().hasSetRecordFields()) {
                } else if (value.getJob().hasCreatePayment()) {
                } else if (value.getJob().hasPopAccount()) {
                } else if (value.getJob().hasInfo()) {
                } else if (value.getJob().hasShutdown()) {
                } else if (value.getJob().hasLog()) {
                } else {
                    log.error("Unknown job type {}", value.getJob());
                }
            } else {
                log.error("Received job without job type {}", value);
            }
        } catch (UnconfiguredException e) {
            log.error("Error while handling job {}", value.getJobId());
        }
    }

    @Override
    public void onError(Throwable t) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onCompleted'");
    }

}
