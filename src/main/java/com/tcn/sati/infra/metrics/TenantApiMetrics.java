package com.tcn.sati.infra.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of per-tenant {@link ApiMetrics} instances.
 *
 * <p>In multi-tenant deployments every request is attributed to a tenant key
 * (derived from the {@code {org}} path param for {@code /api/orgs/{org}/...}
 * routes) or falls back to {@link #GLOBAL_KEY} for admin/non-tenant routes.
 * Each tenant gets its own ApiMetrics instance — snapshots stay isolated
 * so dashboards can display per-tenant traffic without cross-tenant mixing.
 */
public class TenantApiMetrics {

    /** Bucket for requests that aren't scoped to a specific tenant. */
    public static final String GLOBAL_KEY = "_global";

    private final ConcurrentHashMap<String, ApiMetrics> byTenant = new ConcurrentHashMap<>();

    public void record(String tenantKey, String method, String pathTemplate, int status, long nanos) {
        String key = (tenantKey == null || tenantKey.isBlank()) ? GLOBAL_KEY : tenantKey;
        byTenant.computeIfAbsent(key, k -> new ApiMetrics()).record(method, pathTemplate, status, nanos);
    }

    /** Snapshot for a single tenant (empty list if unknown). */
    public List<Map<String, Object>> snapshot(String tenantKey) {
        ApiMetrics m = byTenant.get(tenantKey);
        return m != null ? m.snapshot() : List.of();
    }

    /** Set of keys currently tracked (includes {@link #GLOBAL_KEY} if any non-tenant requests have been seen). */
    public Set<String> tenants() {
        return byTenant.keySet();
    }

    /**
     * Aggregate snapshot: merges rows across all tenants by method+path so the
     * legacy {@code /api/admin/api-metrics} endpoint can report overall traffic.
     * Counters sum; latency percentiles are recomputed from the merged rows
     * using a sum-weighted approximation (avg is exact; p50/p95/p99 are the
     * max across buckets — a conservative upper bound suitable for dashboards).
     */
    public List<Map<String, Object>> snapshotAll() {
        Map<String, Map<String, Object>> merged = new HashMap<>();
        for (ApiMetrics m : byTenant.values()) {
            for (Map<String, Object> row : m.snapshot()) {
                String key = (String) row.get("key");
                Map<String, Object> existing = merged.get(key);
                if (existing == null) {
                    merged.put(key, new LinkedHashMap<>(row));
                } else {
                    mergeRow(existing, row);
                }
            }
        }
        var out = new ArrayList<>(merged.values());
        out.sort((a, b) -> {
            long ac = ((Number) a.get("total")).longValue();
            long bc = ((Number) b.get("total")).longValue();
            int cmp = Long.compare(bc, ac);
            if (cmp != 0) return cmp;
            return ((String) a.get("key")).compareTo((String) b.get("key"));
        });
        return out;
    }

    private static void mergeRow(Map<String, Object> into, Map<String, Object> from) {
        into.put("total", ((Number) into.get("total")).longValue() + ((Number) from.get("total")).longValue());
        into.put("errors", ((Number) into.get("errors")).longValue() + ((Number) from.get("errors")).longValue());
        into.put("clientErrors",
                ((Number) into.get("clientErrors")).longValue() + ((Number) from.get("clientErrors")).longValue());
        // Latency merge: weighted avg by total, max for percentiles.
        long tInto = ((Number) into.get("total")).longValue();
        long tFrom = ((Number) from.get("total")).longValue();
        double wAvg = tInto + tFrom == 0 ? 0
                : (((Number) into.get("avgMs")).doubleValue() * (tInto - tFrom)
                        + ((Number) from.get("avgMs")).doubleValue() * tFrom) / tInto;
        into.put("avgMs", Math.round(wAvg));
        into.put("p50Ms", Math.max(((Number) into.get("p50Ms")).longValue(), ((Number) from.get("p50Ms")).longValue()));
        into.put("p95Ms", Math.max(((Number) into.get("p95Ms")).longValue(), ((Number) from.get("p95Ms")).longValue()));
        into.put("p99Ms", Math.max(((Number) into.get("p99Ms")).longValue(), ((Number) from.get("p99Ms")).longValue()));
    }

    public void reset() {
        byTenant.values().forEach(ApiMetrics::reset);
    }

    /** Read-only view for diagnostics. */
    Collection<ApiMetrics> instances() {
        return byTenant.values();
    }
}
