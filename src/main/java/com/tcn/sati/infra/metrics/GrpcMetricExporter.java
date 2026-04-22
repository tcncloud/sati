package com.tcn.sati.infra.metrics;

import com.tcn.sati.infra.gate.GateClient;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * OTel MetricExporter that ships metric data to Gate via GateClient.reportMetrics().
 * All exceptions are caught — telemetry must never break the application.
 */
public final class GrpcMetricExporter implements MetricExporter {

    private static final Logger log = LoggerFactory.getLogger(GrpcMetricExporter.class);

    private final GateClient gateClient;
    private final String clientId;

    public GrpcMetricExporter(GateClient gateClient, String clientId) {
        this.gateClient = gateClient;
        this.clientId = clientId;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        try {
            int accepted = gateClient.reportMetrics(clientId, metrics);
            log.debug("Exported {} metric series ({} accepted)", metrics.size(), accepted);
            return CompletableResultCode.ofSuccess();
        } catch (Exception e) {
            log.debug("Failed to export metrics: {}", e.getMessage());
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }
}
