package com.tcn.sati.infra.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-endpoint HTTP metrics, populated by {@link ApiMetricsRecorder} for every
 * Javalin request and surfaced on the dashboard.
 *
 * <p>Keyed on {@code METHOD route_template} (e.g. {@code "POST /api/admin/config"}) so that
 * path-templated routes don't explode the cardinality — all calls to
 * {@code /api/scrublists/42/entries} aggregate into the {@code /api/scrublists/{id}/entries}
 * row.
 *
 * <p>Thread-safe for concurrent record() calls. {@link #snapshot()} is O(n log n) per call
 * but is only invoked on dashboard poll, where n is small (distinct endpoints).
 */
public class ApiMetrics {

    private final ConcurrentHashMap<String, EndpointStats> byEndpoint = new ConcurrentHashMap<>();

    /** Record a completed HTTP request. */
    public void record(String method, String pathTemplate, int status, long nanos) {
        final String m = (method == null) ? "UNKNOWN" : method;
        final String p = (pathTemplate == null || pathTemplate.isBlank()) ? "(unknown)" : pathTemplate;
        var key = m + " " + p;
        var stats = byEndpoint.computeIfAbsent(key, k -> new EndpointStats(m, p));
        stats.record(status, nanos);
    }

    /**
     * Snapshot sorted by traffic volume (busiest first), with enough data for the dashboard table:
     * method, path, total, errors, 4xx, avg/p50/p95/p99 ms.
     */
    public List<Map<String, Object>> snapshot() {
        var out = new ArrayList<Map<String, Object>>(byEndpoint.size());
        for (var stats : byEndpoint.values()) {
            out.add(stats.snapshot());
        }
        out.sort((a, b) -> {
            long ac = ((Number) a.get("total")).longValue();
            long bc = ((Number) b.get("total")).longValue();
            int cmp = Long.compare(bc, ac);
            if (cmp != 0) return cmp;
            return ((String) a.get("key")).compareTo((String) b.get("key"));
        });
        return out;
    }

    public void reset() {
        byEndpoint.clear();
    }

    private static final class EndpointStats {
        final String method;
        final String path;
        final AtomicLong total = new AtomicLong();
        final AtomicLong errors = new AtomicLong();       // status >= 500
        final AtomicLong clientErrors = new AtomicLong(); // 4xx
        final RollingDurations durations = new RollingDurations(1000);

        EndpointStats(String method, String path) {
            this.method = method;
            this.path = path;
        }

        void record(int status, long nanos) {
            total.incrementAndGet();
            if (status >= 500) {
                errors.incrementAndGet();
            } else if (status >= 400) {
                clientErrors.incrementAndGet();
            }
            durations.record(nanos);
        }

        Map<String, Object> snapshot() {
            var m = new LinkedHashMap<String, Object>();
            m.put("key", method + " " + path);
            m.put("method", method);
            m.put("path", path);
            m.put("total", total.get());
            m.put("errors", errors.get());
            m.put("clientErrors", clientErrors.get());
            var snap = durations.snapshot();
            m.put("avgMs", snap.count() > 0 ? Math.round(snap.avg() / 1_000_000.0) : 0);
            m.put("p50Ms", Math.round(snap.p50() / 1_000_000.0));
            m.put("p95Ms", Math.round(snap.p95() / 1_000_000.0));
            m.put("p99Ms", Math.round(snap.p99() / 1_000_000.0));
            return m;
        }
    }

    /** Rolling window of request durations in nanoseconds. */
    private static final class RollingDurations {
        private final long[] buf;
        private int pos = 0;
        private int count = 0;

        RollingDurations(int capacity) {
            this.buf = new long[capacity];
        }

        synchronized void record(long nanos) {
            buf[pos] = nanos;
            pos = (pos + 1) % buf.length;
            if (count < buf.length) count++;
        }

        synchronized Snapshot snapshot() {
            if (count == 0) return Snapshot.EMPTY;
            long[] copy = new long[count];
            System.arraycopy(buf, 0, copy, 0, count);
            java.util.Arrays.sort(copy);
            double sum = 0;
            for (long v : copy) sum += v;
            return new Snapshot(
                    count, sum / count,
                    copy[pctIdx(count, 50)], copy[pctIdx(count, 95)], copy[pctIdx(count, 99)]);
        }

        private static int pctIdx(int n, int pct) {
            return Math.min(n - 1, (int) Math.ceil(n * pct / 100.0) - 1);
        }

        record Snapshot(int count, double avg, double p50, double p95, double p99) {
            static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0);
        }
    }
}
