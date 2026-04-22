package com.tcn.sati.infra.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Logback appender that captures log messages in memory for admin log viewing
 * and remote log shipping to Gate.
 *
 * Supports:
 * - Static accessors for admin dashboard (unchanged API)
 * - OTel trace context extraction (traceId/spanId captured at append time)
 * - Log shipping to Gate via pluggable LogShipper interface
 *
 * Configure in logback.xml:
 * <pre>
 * &lt;appender name="MEMORY" class="com.tcn.sati.infra.logging.MemoryLogAppender"/&gt;
 * &lt;root level="INFO"&gt;
 *     &lt;appender-ref ref="MEMORY"/&gt;
 * &lt;/root&gt;
 * </pre>
 */
public class MemoryLogAppender extends AppenderBase<ILoggingEvent> {

    private static final Logger log = LoggerFactory.getLogger(MemoryLogAppender.class);

    private static final int MAX_EVENTS = 1000;
    private static final String PATTERN = "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n";
    private static final long SHIP_INTERVAL_MS = 10_000;

    private static final ConcurrentLinkedDeque<LogEntry> events = new ConcurrentLinkedDeque<>();
    private static final AtomicReference<LogShipper> shipperRef = new AtomicReference<>();
    private static volatile TraceContextExtractor traceExtractor;
    private static volatile Thread shipperThread;

    private PatternLayout layout;

    // ========== Logback lifecycle ==========

    @Override
    public void start() {
        layout = new PatternLayout();
        layout.setContext(getContext());
        layout.setPattern(PATTERN);
        layout.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (layout != null) {
            layout.stop();
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        String formatted = layout.doLayout(event);

        // Capture OTel trace context at append time if extractor is registered.
        String traceId = null;
        String spanId = null;
        TraceContextExtractor extractor = traceExtractor;
        if (extractor != null) {
            try {
                traceId = extractor.traceId();
                spanId = extractor.spanId();
            } catch (Throwable ignored) {}
        }

        // Capture MDC map (includes traceId/spanId set by WorkStreamClient).
        Map<String, String> mdcMap = event.getMDCPropertyMap();
        Map<String, String> mdc = (mdcMap != null && !mdcMap.isEmpty()) ? new HashMap<>(mdcMap) : null;

        if (traceId == null && mdc != null) {
            traceId = mdc.get("traceId");
            spanId = mdc.get("spanId");
        }

        // Capture stack trace if present.
        String stackTrace = null;
        if (event.getThrowableProxy() != null) {
            stackTrace = ThrowableProxyUtil.asString(event.getThrowableProxy());
        }

        events.addLast(new LogEntry(
                formatted,
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                traceId,
                spanId,
                mdc,
                stackTrace));

        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    // ========== Shipping ==========

    /**
     * Register a TraceContextExtractor so traceId/spanId are captured on every log event.
     * Call this once at startup (e.g. from TenantContext) with a lambda that reads from
     * GlobalOpenTelemetry's current span.
     */
    public static void setTraceContextExtractor(TraceContextExtractor extractor) {
        traceExtractor = extractor;
    }

    /**
     * Start shipping logs to Gate. Starts a background daemon thread that drains
     * the in-memory queue to the shipper every 10 seconds.
     */
    public static synchronized void enableLogShipper(LogShipper shipper) {
        shipperRef.set(shipper);
        if (shipperThread == null || !shipperThread.isAlive()) {
            shipperThread = Thread.ofVirtual().name("sati-log-shipper").start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(SHIP_INTERVAL_MS);
                        drainToShipper();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable t) {
                        // Shipper must never crash the app.
                        log.debug("Log shipper error", t);
                    }
                }
            });
        }
    }

    /**
     * Stop shipping logs to Gate.
     */
    public static synchronized void disableLogShipper() {
        shipperRef.set(null);
        Thread t = shipperThread;
        if (t != null) {
            t.interrupt();
            shipperThread = null;
        }
        LogShipper s = shipperRef.getAndSet(null);
        if (s != null) {
            try { s.stop(); } catch (Throwable ignored) {}
        }
    }

    private static void drainToShipper() {
        LogShipper shipper = shipperRef.get();
        if (shipper == null || events.isEmpty()) return;
        List<LogEntry> batch = new ArrayList<>(events);
        if (!batch.isEmpty()) {
            shipper.shipStructuredLogs(batch);
        }
    }

    // ========== Static accessors (admin dashboard) ==========

    public static List<String> getRecentLogs() {
        List<LogEntry> snapshot = new ArrayList<>(events);
        Collections.reverse(snapshot);
        List<String> result = new ArrayList<>(snapshot.size());
        for (LogEntry entry : snapshot) {
            result.add(entry.message);
        }
        return result;
    }

    public static List<String> getRecentLogs(int limit) {
        List<String> all = getRecentLogs();
        return all.subList(0, Math.min(limit, all.size()));
    }

    public static List<LogEntry> getEntriesInTimeRange(long startMs, long endMs) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : events) {
            if (entry.timestampMs >= startMs && entry.timestampMs <= endMs) {
                result.add(entry);
            }
        }
        Collections.reverse(result);
        return result;
    }

    public static List<String> getLogsInTimeRange(long startTimeMs, long endTimeMs) {
        List<String> result = new ArrayList<>();
        for (LogEntry entry : events) {
            if (entry.timestampMs >= startTimeMs && entry.timestampMs <= endTimeMs) {
                result.add(entry.message);
            }
        }
        Collections.reverse(result);
        return result;
    }

    public static int size() { return events.size(); }
    public static void clear() { events.clear(); }

    // ========== Interfaces ==========

    /**
     * Extracts OTel trace/span IDs from the current execution context.
     * Implement using GlobalOpenTelemetry.getTracer(...) or Span.current().
     */
    public interface TraceContextExtractor {
        String traceId();
        String spanId();
    }

    /**
     * Receives batches of structured log entries for remote shipping.
     */
    public interface LogShipper {
        void shipStructuredLogs(List<LogEntry> entries);
        default void stop() {}
    }

    // ========== LogEntry ==========

    /** A structured log entry with full metadata for remote shipping. */
    public static class LogEntry {
        public final String message;       // formatted line (for admin dashboard)
        public final long timestampMs;
        public final String level;
        public final String loggerName;
        public final String threadName;
        public final String rawMessage;    // unformatted message text
        public final String traceId;
        public final String spanId;
        public final Map<String, String> mdc;
        public final String stackTrace;

        LogEntry(String message, long timestampMs, String level, String loggerName,
                 String threadName, String rawMessage, String traceId, String spanId,
                 Map<String, String> mdc, String stackTrace) {
            this.message = message;
            this.timestampMs = timestampMs;
            this.level = level;
            this.loggerName = loggerName;
            this.threadName = threadName;
            this.rawMessage = rawMessage;
            this.traceId = traceId;
            this.spanId = spanId;
            this.mdc = mdc;
            this.stackTrace = stackTrace;
        }
    }
}
