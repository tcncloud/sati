package com.tcn.exile.internal;

import com.tcn.exile.StreamStatus;
import com.tcn.exile.service.TelemetryService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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

  private final OpenTelemetrySdk openTelemetry;
  private final SdkMeterProvider meterProvider;
  private final Meter meter;
  private final DoubleHistogram workDuration;
  private final DoubleHistogram methodDuration;
  private final LongCounter methodCalls;
  private final DoubleHistogram reconnectDuration;

  private static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
  private static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("status");

  /**
   * @param telemetryService gRPC stub for reporting metrics
   * @param clientId unique client identifier
   * @param orgId organization ID (from config poll)
   * @param certificateName certificate name from the config file
   * @param statusSupplier supplies current WorkStream status snapshot
   */
  public MetricsManager(
      TelemetryService telemetryService,
      String clientId,
      String orgId,
      String certificateName,
      Supplier<StreamStatus> statusSupplier) {

    var exporter = new GrpcMetricExporter(telemetryService, clientId);
    var reader = PeriodicMetricReader.builder(exporter).setInterval(Duration.ofSeconds(60)).build();

    var resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(
                        AttributeKey.stringKey("exile.org_id"), orgId,
                        AttributeKey.stringKey("exile.certificate_name"), certificateName,
                        AttributeKey.stringKey("exile.client_id"), clientId)));

    this.meterProvider =
        SdkMeterProvider.builder().setResource(resource).registerMetricReader(reader).build();

    // TracerProvider generates valid trace/span IDs for log correlation.
    var tracerProvider = SdkTracerProvider.builder().setResource(resource).build();

    var sdkBuilder =
        OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .setTracerProvider(tracerProvider);

    OpenTelemetrySdk sdk;
    try {
      sdk = sdkBuilder.buildAndRegisterGlobal();
    } catch (IllegalStateException e) {
      // Already registered (e.g. multi-tenant or restart) — build without registering.
      sdk = sdkBuilder.build();
    }
    this.openTelemetry = sdk;

    this.meter = meterProvider.get("com.tcn.exile.sati");

    // --- Built-in instruments ---

    // WorkStream counters (cumulative, read from StreamStatus)
    meter
        .counterBuilder("exile.work.completed")
        .setDescription("Total work items completed since start")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(statusSupplier.get().completedTotal()));

    meter
        .counterBuilder("exile.work.failed")
        .setDescription("Total work items that failed since start")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(statusSupplier.get().failedTotal()));

    meter
        .counterBuilder("exile.work.reconnects")
        .setDescription("Total stream reconnection attempts since start")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(statusSupplier.get().reconnectAttempts()));

    // WorkStream gauges
    meter
        .gaugeBuilder("exile.work.inflight")
        .ofLongs()
        .setDescription("Work items currently being processed")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(statusSupplier.get().inflight()));

    meter
        .gaugeBuilder("exile.work.phase")
        .ofLongs()
        .setDescription(
            "WorkStream phase (0=IDLE, 1=CONNECTING, 2=REGISTERING, 3=ACTIVE, 4=RECONNECTING,"
                + " 5=CLOSED, 6=DRAINING)")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(statusSupplier.get().phase().ordinal()));

    // JVM gauges
    var memoryBean = ManagementFactory.getMemoryMXBean();
    var threadBean = ManagementFactory.getThreadMXBean();

    meter
        .gaugeBuilder("exile.jvm.heap_used")
        .ofLongs()
        .setDescription("JVM heap memory used")
        .setUnit("bytes")
        .buildWithCallback(obs -> obs.record(memoryBean.getHeapMemoryUsage().getUsed()));

    meter
        .gaugeBuilder("exile.jvm.threads")
        .ofLongs()
        .setDescription("JVM thread count")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(threadBean.getThreadCount()));

    // Work duration histogram (recorded externally via recordWorkDuration)
    this.workDuration =
        meter
            .histogramBuilder("exile.work.duration")
            .setDescription("Time to process a work item (job or event)")
            .setUnit("s")
            .build();

    // Per-method metrics (method name as attribute)
    this.methodDuration =
        meter
            .histogramBuilder("exile.plugin.duration")
            .setDescription("Time to execute a plugin method")
            .setUnit("s")
            .build();

    this.methodCalls =
        meter
            .counterBuilder("exile.plugin.calls")
            .setDescription("Plugin method invocations")
            .setUnit("1")
            .build();

    this.reconnectDuration =
        meter
            .histogramBuilder("exile.work.reconnect_duration")
            .setDescription("Time from stream disconnect to successful re-registration")
            .setUnit("s")
            .build();

    log.info(
        "MetricsManager initialized (export interval=60s, clientId={}, orgId={}, certificateName={})",
        clientId,
        orgId,
        certificateName);
  }

  /** The OTel Meter for plugin developers to create custom instruments. */
  public Meter meter() {
    return meter;
  }

  /** Record the duration of a completed work item. Called from WorkStreamClient. */
  public void recordWorkDuration(double seconds) {
    workDuration.record(seconds);
  }

  /** Record the time from disconnect to successful re-registration. */
  public void recordReconnectDuration(double seconds) {
    reconnectDuration.record(seconds);
  }

  /** Record a plugin method call with duration and success/failure status. */
  public void recordMethodCall(String method, double durationSeconds, boolean success) {
    var attrs = Attributes.of(METHOD_KEY, method, STATUS_KEY, success ? "ok" : "error");
    methodCalls.add(1, attrs);
    methodDuration.record(durationSeconds, attrs);
  }

  /**
   * Register gauges exposing the adaptive controller's internal state. Called once by {@link
   * com.tcn.exile.ExileClient} after the adaptive instance is constructed. No-op when the caller
   * disabled adaptive mode — in that case there's nothing to observe.
   */
  public void registerAdaptiveGauges(AdaptiveCapacity adaptive) {
    meter
        .gaugeBuilder("exile.adaptive.limit")
        .ofLongs()
        .setDescription("Current adaptive controller target (work items in flight)")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(adaptive.limit()));

    meter
        .gaugeBuilder("exile.adaptive.job_p95_ms")
        .setDescription("Job p95 latency observed by the adaptive controller (sliding window)")
        .setUnit("ms")
        .buildWithCallback(obs -> obs.record(adaptive.jobP95Nanos() / 1_000_000.0));

    meter
        .gaugeBuilder("exile.adaptive.job_ema_ms")
        .setDescription("Job EMA latency observed by the adaptive controller")
        .setUnit("ms")
        .buildWithCallback(obs -> obs.record(adaptive.jobEmaNanos() / 1_000_000.0));

    meter
        .gaugeBuilder("exile.adaptive.decaying_min_ms")
        .setDescription("Decaying minimum job latency used by the Vegas-style gradient")
        .setUnit("ms")
        .buildWithCallback(obs -> obs.record(adaptive.decayingMinNanos() / 1_000_000.0));

    meter
        .gaugeBuilder("exile.adaptive.slo_gradient")
        .setDescription("SLO gradient at last recompute (SLO / jobP95, clamped 0.5..1.0)")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(adaptive.lastSloGradient()));

    meter
        .gaugeBuilder("exile.adaptive.min_gradient")
        .setDescription("Min gradient at last recompute (decayingMin / jobEMA, clamped 0.5..1.0)")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(adaptive.lastMinGradient()));

    meter
        .gaugeBuilder("exile.adaptive.resource_gradient")
        .setDescription(
            "Resource gradient at last recompute (min across plugin-declared resources)")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(adaptive.lastResourceGradient()));

    meter
        .gaugeBuilder("exile.adaptive.effective_ceiling")
        .ofLongs()
        .setDescription(
            "Effective ceiling: min(maxLimit, minimum hardMax across declared resources)")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(adaptive.effectiveCeiling()));

    meter
        .counterBuilder("exile.adaptive.errors")
        .setDescription("Cumulative plugin errors observed by the adaptive controller")
        .setUnit("1")
        .buildWithCallback(obs -> obs.record(adaptive.errorCount()));

    log.info(
        "Adaptive concurrency gauges registered (limit={}, min={}, max={}, ceiling={})",
        adaptive.limit(),
        adaptive.minLimit(),
        adaptive.maxLimit(),
        adaptive.effectiveCeiling());
  }

  @Override
  public void close() {
    openTelemetry.close();
    log.info("MetricsManager shut down");
  }
}
