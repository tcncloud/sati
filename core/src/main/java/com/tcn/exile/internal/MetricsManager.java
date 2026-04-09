package com.tcn.exile.internal;

import com.tcn.exile.StreamStatus;
import com.tcn.exile.service.TelemetryService;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets up OTel SDK metric collection with built-in exile instruments and a custom gRPC exporter.
 * Exposes the {@link Meter} so plugin developers can register their own instruments.
 */
public final class MetricsManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MetricsManager.class);

  private final SdkMeterProvider meterProvider;
  private final Meter meter;
  private final DoubleHistogram workDuration;

  public MetricsManager(
      TelemetryService telemetryService,
      String clientId,
      Supplier<StreamStatus> statusSupplier) {

    var exporter = new GrpcMetricExporter(telemetryService, clientId);
    var reader =
        PeriodicMetricReader.builder(exporter).setInterval(Duration.ofSeconds(60)).build();

    this.meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    this.meter = meterProvider.get("com.tcn.exile.sati");

    // --- Built-in instruments ---

    // WorkStream counters (cumulative, read from StreamStatus)
    meter
        .counterBuilder("exile.work.completed")
        .setDescription("Total work items completed since start")
        .setUnit("1")
        .buildWithCallback(
            obs -> obs.record(statusSupplier.get().completedTotal()));

    meter
        .counterBuilder("exile.work.failed")
        .setDescription("Total work items that failed since start")
        .setUnit("1")
        .buildWithCallback(
            obs -> obs.record(statusSupplier.get().failedTotal()));

    meter
        .counterBuilder("exile.work.reconnects")
        .setDescription("Total stream reconnection attempts since start")
        .setUnit("1")
        .buildWithCallback(
            obs -> obs.record(statusSupplier.get().reconnectAttempts()));

    // WorkStream gauges
    meter
        .gaugeBuilder("exile.work.inflight")
        .ofLongs()
        .setDescription("Work items currently being processed")
        .setUnit("1")
        .buildWithCallback(
            obs -> obs.record(statusSupplier.get().inflight()));

    meter
        .gaugeBuilder("exile.work.phase")
        .ofLongs()
        .setDescription("WorkStream phase (0=IDLE, 1=CONNECTING, 2=REGISTERING, 3=ACTIVE, 4=RECONNECTING, 5=CLOSED)")
        .setUnit("1")
        .buildWithCallback(
            obs -> obs.record(statusSupplier.get().phase().ordinal()));

    // JVM gauges
    var memoryBean = ManagementFactory.getMemoryMXBean();
    var threadBean = ManagementFactory.getThreadMXBean();

    meter
        .gaugeBuilder("exile.jvm.heap_used")
        .ofLongs()
        .setDescription("JVM heap memory used")
        .setUnit("bytes")
        .buildWithCallback(
            obs -> obs.record(memoryBean.getHeapMemoryUsage().getUsed()));

    meter
        .gaugeBuilder("exile.jvm.threads")
        .ofLongs()
        .setDescription("JVM thread count")
        .setUnit("1")
        .buildWithCallback(
            obs -> obs.record(threadBean.getThreadCount()));

    // Work duration histogram (recorded externally via recordWorkDuration)
    this.workDuration =
        meter
            .histogramBuilder("exile.work.duration")
            .setDescription("Time to process a work item (job or event)")
            .setUnit("s")
            .build();

    log.info("MetricsManager initialized (export interval=60s, clientId={})", clientId);
  }

  /** The OTel Meter for plugin developers to create custom instruments. */
  public Meter meter() {
    return meter;
  }

  /** Record the duration of a completed work item. Called from WorkStreamClient. */
  public void recordWorkDuration(double seconds) {
    workDuration.record(seconds);
  }

  @Override
  public void close() {
    meterProvider.close();
    log.info("MetricsManager shut down");
  }
}
