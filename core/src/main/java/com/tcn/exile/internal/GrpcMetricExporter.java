package com.tcn.exile.internal;

import com.tcn.exile.service.TelemetryService;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTel MetricExporter that sends metric data to the gate via the TelemetryService gRPC endpoint.
 * All exceptions are caught and logged — telemetry must never break the application.
 */
public final class GrpcMetricExporter implements MetricExporter {

  private static final Logger log = LoggerFactory.getLogger(GrpcMetricExporter.class);

  private final TelemetryService telemetryService;
  private final String clientId;

  public GrpcMetricExporter(TelemetryService telemetryService, String clientId) {
    this.telemetryService = telemetryService;
    this.clientId = clientId;
  }

  @Override
  public CompletableResultCode export(Collection<MetricData> metrics) {
    try {
      int accepted = telemetryService.reportMetrics(clientId, metrics);
      log.debug("Exported {} metric data points ({} accepted)", metrics.size(), accepted);
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
