package com.tcn.exile.service;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.opentelemetry.sdk.metrics.data.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Reports client metrics and logs to the gate TelemetryService. */
public final class TelemetryService {

  private final TelemetryServiceGrpc.TelemetryServiceBlockingStub stub;

  TelemetryService(ManagedChannel channel) {
    this.stub = TelemetryServiceGrpc.newBlockingStub(channel);
  }

  /** Send a batch of OTel metric data to the gate. Returns accepted count. */
  public int reportMetrics(String clientId, Collection<MetricData> metrics) {
    var now = Instant.now();
    var builder =
        ReportMetricsRequest.newBuilder()
            .setClientId(clientId)
            .setCollectionTime(toTimestamp(now));

    for (var metric : metrics) {
      var name = metric.getName();
      var description = metric.getDescription();
      var unit = metric.getUnit();

      switch (metric.getType()) {
        case LONG_GAUGE -> {
          for (var point : metric.getLongGaugeData().getPoints()) {
            builder.addDataPoints(
                MetricDataPoint.newBuilder()
                    .setName(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .setType(MetricType.METRIC_TYPE_GAUGE)
                    .putAllAttributes(attributesToMap(point.getAttributes()))
                    .setTime(toTimestamp(point.getEpochNanos()))
                    .setDoubleValue(point.getValue())
                    .build());
          }
        }
        case DOUBLE_GAUGE -> {
          for (var point : metric.getDoubleGaugeData().getPoints()) {
            builder.addDataPoints(
                MetricDataPoint.newBuilder()
                    .setName(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .setType(MetricType.METRIC_TYPE_GAUGE)
                    .putAllAttributes(attributesToMap(point.getAttributes()))
                    .setTime(toTimestamp(point.getEpochNanos()))
                    .setDoubleValue(point.getValue())
                    .build());
          }
        }
        case LONG_SUM -> {
          for (var point : metric.getLongSumData().getPoints()) {
            builder.addDataPoints(
                MetricDataPoint.newBuilder()
                    .setName(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .setType(MetricType.METRIC_TYPE_SUM)
                    .putAllAttributes(attributesToMap(point.getAttributes()))
                    .setTime(toTimestamp(point.getEpochNanos()))
                    .setIntValue(point.getValue())
                    .build());
          }
        }
        case DOUBLE_SUM -> {
          for (var point : metric.getDoubleSumData().getPoints()) {
            builder.addDataPoints(
                MetricDataPoint.newBuilder()
                    .setName(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .setType(MetricType.METRIC_TYPE_SUM)
                    .putAllAttributes(attributesToMap(point.getAttributes()))
                    .setTime(toTimestamp(point.getEpochNanos()))
                    .setDoubleValue(point.getValue())
                    .build());
          }
        }
        case HISTOGRAM -> {
          for (var point : metric.getHistogramData().getPoints()) {
            var hv =
                HistogramValue.newBuilder()
                    .setCount(point.getCount())
                    .setSum(point.getSum())
                    .addAllBoundaries(point.getBoundaries())
                    .addAllBucketCounts(point.getCounts())
                    .setMin(point.getMin())
                    .setMax(point.getMax());
            builder.addDataPoints(
                MetricDataPoint.newBuilder()
                    .setName(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .setType(MetricType.METRIC_TYPE_HISTOGRAM)
                    .putAllAttributes(attributesToMap(point.getAttributes()))
                    .setTime(toTimestamp(point.getEpochNanos()))
                    .setHistogramValue(hv)
                    .build());
          }
        }
        default -> {} // skip unsupported types
      }
    }

    var resp = stub.reportMetrics(builder.build());
    return resp.getAcceptedCount();
  }

  /** Send a batch of structured log records to the gate. Returns accepted count. */
  public int reportLogs(String clientId, List<LogRecord> records) {
    var builder = ReportLogsRequest.newBuilder().setClientId(clientId).addAllRecords(records);
    var resp = stub.reportLogs(builder.build());
    return resp.getAcceptedCount();
  }

  private static java.util.Map<String, String> attributesToMap(
      io.opentelemetry.api.common.Attributes attrs) {
    var map = new java.util.HashMap<String, String>();
    attrs.forEach((k, v) -> map.put(k.getKey(), String.valueOf(v)));
    return map;
  }

  private static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static Timestamp toTimestamp(long epochNanos) {
    return Timestamp.newBuilder()
        .setSeconds(epochNanos / 1_000_000_000L)
        .setNanos((int) (epochNanos % 1_000_000_000L))
        .build();
  }
}
