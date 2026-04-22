package com.tcn.sati.infra.metrics;

import com.tcn.sati.infra.gate.GateClient;
import com.tcn.sati.infra.gate.WorkStreamClient;
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
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * Sets up OTel SDK metric collection with built-in exile instruments and a custom gRPC exporter.
 * Exposes {@link #meter()} so application developers can register their own instruments.
 *
 * Built-in instruments:
 *   exile.work.completed    — cumulative counter of successfully processed work items
 *   exile.work.failed       — cumulative counter of failed work items
 *   exile.work.reconnects   — cumulative counter of stream reconnection attempts
 *   exile.work.inflight     — gauge: work items currently being processed
 *   exile.work.phase        — gauge: WorkStream phase ordinal (0=IDLE..5=CLOSED)
 *   exile.jvm.heap_used     — gauge: JVM heap used (bytes)
 *   exile.jvm.threads       — gauge: JVM thread count
 *   exile.work.duration     — histogram: seconds per completed work item
 *   exile.work.reconnect_duration — histogram: seconds from disconnect to re-registration
 */
public final class MetricsManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsManager.class);

    private static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
    private static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("status");

    private final OpenTelemetrySdk openTelemetry;
    private final Meter meter;
    private final DoubleHistogram workDuration;
    private final DoubleHistogram reconnectDuration;
    private final DoubleHistogram methodDuration;
    private final LongCounter methodCalls;

    /**
     * @param gateClient               used by GrpcMetricExporter to ship metrics
     * @param clientId                 unique identifier for this client instance
     * @param orgId                    organization ID (from SatiConfig.org())
     * @param certName                 certificate / config name (tenant key if unknown)
     * @param workStream               WorkStreamClient for built-in gauge/counter callbacks
     * @param tracingSamplingFraction  GCP Cloud Trace sampling fraction; 0.0 disables tracing
     */
    public MetricsManager(GateClient gateClient, String clientId, String orgId,
                          String certName, WorkStreamClient workStream, double tracingSamplingFraction) {

        var exporter = new GrpcMetricExporter(gateClient, clientId);
        var reader = PeriodicMetricReader.builder(exporter).setInterval(Duration.ofSeconds(60)).build();

        var resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("exile.org_id"), orgId,
                AttributeKey.stringKey("exile.certificate_name"), certName,
                AttributeKey.stringKey("exile.client_id"), clientId)));

        var meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(reader)
                .build();

        SdkTracerProviderBuilder tracerBuilder = SdkTracerProvider.builder().setResource(resource);
        if (tracingSamplingFraction > 0.0) {
            SpanExporter spanExporter = tryBuildCloudTraceExporter();
            if (spanExporter != null) {
                tracerBuilder.addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                             .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(tracingSamplingFraction)));
                log.info("Tracing enabled: GCP Cloud Trace exporter, sampling fraction={}", tracingSamplingFraction);
            } else {
                tracerBuilder.setSampler(Sampler.alwaysOff());
            }
        } else {
            tracerBuilder.setSampler(Sampler.alwaysOff());
        }
        var tracerProvider = tracerBuilder.build();

        OpenTelemetrySdk sdk;
        try {
            sdk = OpenTelemetrySdk.builder()
                    .setMeterProvider(meterProvider)
                    .setTracerProvider(tracerProvider)
                    .buildAndRegisterGlobal();
        } catch (IllegalStateException e) {
            // Already registered (e.g. restart or multi-tenant) — build without registering globally.
            sdk = OpenTelemetrySdk.builder()
                    .setMeterProvider(meterProvider)
                    .setTracerProvider(tracerProvider)
                    .build();
        }
        this.openTelemetry = sdk;
        this.meter = meterProvider.get("com.tcn.sati");

        // --- Built-in WorkStream counters (cumulative) ---

        meter.counterBuilder("exile.work.completed")
                .setDescription("Total work items completed since start")
                .setUnit("1")
                .buildWithCallback(obs -> obs.record(workStream.getCompletedTotal()));

        meter.counterBuilder("exile.work.failed")
                .setDescription("Total work items that failed since start")
                .setUnit("1")
                .buildWithCallback(obs -> obs.record(workStream.getFailedTotal()));

        meter.counterBuilder("exile.work.reconnects")
                .setDescription("Total stream reconnection attempts since start")
                .setUnit("1")
                .buildWithCallback(obs -> obs.record(workStream.getReconnectAttempts()));

        // --- Built-in WorkStream gauges ---

        meter.gaugeBuilder("exile.work.inflight")
                .ofLongs()
                .setDescription("Work items currently being processed")
                .setUnit("1")
                .buildWithCallback(obs -> obs.record(workStream.getInflight()));

        meter.gaugeBuilder("exile.work.phase")
                .ofLongs()
                .setDescription("WorkStream phase (0=IDLE,1=CONNECTING,2=REGISTERING,3=ACTIVE,4=RECONNECTING,5=CLOSED,6=DRAINING)")
                .setUnit("1")
                .buildWithCallback(obs -> obs.record(workStream.getPhase().ordinal()));

        // --- JVM gauges ---

        var memBean = ManagementFactory.getMemoryMXBean();
        var threadBean = ManagementFactory.getThreadMXBean();

        meter.gaugeBuilder("exile.jvm.heap_used")
                .ofLongs()
                .setDescription("JVM heap memory used")
                .setUnit("bytes")
                .buildWithCallback(obs -> obs.record(memBean.getHeapMemoryUsage().getUsed()));

        meter.gaugeBuilder("exile.jvm.threads")
                .ofLongs()
                .setDescription("JVM thread count")
                .setUnit("1")
                .buildWithCallback(obs -> obs.record(threadBean.getThreadCount()));

        // --- Histograms (recorded externally via callbacks) ---

        this.workDuration = meter.histogramBuilder("exile.work.duration")
                .setDescription("Time to process a work item")
                .setUnit("s")
                .build();

        this.reconnectDuration = meter.histogramBuilder("exile.work.reconnect_duration")
                .setDescription("Time from stream disconnect to successful re-registration")
                .setUnit("s")
                .build();

        this.methodDuration = meter.histogramBuilder("exile.plugin.duration")
                .setDescription("Time to execute a plugin method")
                .setUnit("s")
                .build();

        this.methodCalls = meter.counterBuilder("exile.plugin.calls")
                .setDescription("Plugin method invocations")
                .setUnit("1")
                .build();

        log.info("MetricsManager initialized (interval=60s, clientId={}, org={}, cert={})",
                clientId, orgId, certName);
    }

    /** The OTel Meter for registering custom instruments. */
    public Meter meter() {
        return meter;
    }

    /** Called from WorkStreamClient when a work item completes. */
    public void recordWorkDuration(double seconds) {
        workDuration.record(seconds);
    }

    /** Called from WorkStreamClient when re-registration succeeds after a disconnect. */
    public void recordReconnectDuration(double seconds) {
        reconnectDuration.record(seconds);
    }

    /** Called from WorkStreamClient for each dispatched job or event with per-method timing. */
    public void recordMethodCall(String method, double durationSeconds, boolean success) {
        var attrs = Attributes.of(METHOD_KEY, method, STATUS_KEY, success ? "ok" : "error");
        methodCalls.add(1, attrs);
        methodDuration.record(durationSeconds, attrs);
    }

    private static SpanExporter tryBuildCloudTraceExporter() {
        try {
            return com.google.cloud.opentelemetry.trace.TraceExporter.createWithDefaultConfiguration();
        } catch (Exception e) {
            log.warn("GCP Cloud Trace exporter unavailable (no credentials or library missing): {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        openTelemetry.close();
        log.info("MetricsManager shut down");
    }
}
