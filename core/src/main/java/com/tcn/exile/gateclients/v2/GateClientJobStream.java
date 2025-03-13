package com.tcn.exile.gateclients.v2;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.LookupType;
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
        if (isUnconfigured()) {
            log.trace("The configuration was not set, we will not start the job stream");
            return;
        }
        if (!plugin.isRunning()) {
            log.trace("The plugin is not running, we will not start the job stream");
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

                plugin.listPools(value.getListPools().getJobId());
            } else if (value.hasGetPoolStatus()) {
                plugin.getPoolStatus(
                    value.getGetPoolStatus().getJobId(),
                    value.getGetPoolStatus().getPoolId());
            } else if (value.hasGetPoolRecords()) {
                plugin.getPoolRecords(value.getGetPoolRecords().getJobId(), value.getGetPoolRecords().getPoolId());
            } else if (value.hasSearchRecords()) {
                String satiParentId = null;
                if (value.getSearchRecords().hasParentId()) {
                    satiParentId = value.getSearchRecords().getParentId().getValue();
                }
                plugin.searchRecords(
                        value.getSearchRecords().getJobId(),
                        LookupType.valueOf(value.getSearchRecords().getLookupType().toUpperCase().strip()),
                        value.getSearchRecords().getLookupValue(),
                        satiParentId
                );
            } else if (value.hasGetRecordFields()) {
                plugin.readFields(
                        value.getGetRecordFields().getJobId(),
                        value.getGetRecordFields().getPoolId(),
                        value.getGetRecordFields().getFieldNamesList().toArray(new String[0])
                );
                // plugin.readFields(value.getJobId(), value.getGetRecordFields().getRecordId(),
                // value.getGetRecordFields().getFieldsList().toArray(new String[0]));
            } else if (value.hasSetRecordFields()) {
                Map<String, String> fieldsMap = new HashMap<>();
                for (var f : value.getSetRecordFields().getFieldsList()) {
                  fieldsMap.put(f.getFieldName(), f.getFieldValue());
                }
                plugin.writeFields(
                        value.getSetRecordFields().getJobId(),
                        value.getSetRecordFields().getRecordId(),
                        fieldsMap
                );
            } else if (value.hasCreatePayment()) {
                plugin.createPayment(
                        value.getCreatePayment().getJobId(),
                        value.getCreatePayment().getRecordId(),
                        Map.of("paymentId", value.getCreatePayment().getPaymentId().toString(),
                                "paymentAmount", value.getCreatePayment().getPaymentAmount().toString(),
                                "paymentType", value.getCreatePayment().getPaymentType().toString(),
                                "paymentDate", value.getCreatePayment().getPaymentDate().toString())
                );
            } else if (value.hasPopAccount()) {
                plugin.popAccount(
                        value.getPopAccount().getJobId(),
                        value.getPopAccount().getRecordId(),
                        null,
                        value.getPopAccount().getCallSid(),
                        value.getPopAccount().getCallType()
                );
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
        this.start();
    }

    @Override
    public void onCompleted() {
        this.shutdown();
        this.start();
    }

}
