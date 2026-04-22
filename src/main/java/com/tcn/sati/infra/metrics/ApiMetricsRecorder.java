package com.tcn.sati.infra.metrics;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Registers Javalin before/after handlers that time every HTTP request and feed
 * the result into {@link TenantApiMetrics}.
 *
 * <p>Must be called immediately after {@code Javalin.create(...)} and before any
 * route registrations, so that every subsequently-registered route is instrumented.
 *
 * <p>Tenant attribution: requests under {@code /api/orgs/{org}/...} are recorded
 * against the matched {@code org} path param; everything else (admin, swagger,
 * static files) is recorded under {@link TenantApiMetrics#GLOBAL_KEY}. This keeps
 * per-tenant rollups clean and prevents admin traffic from being attributed to a
 * tenant whose key happens to appear in the URL.
 */
public final class ApiMetricsRecorder {

    private static final String ATTR_START = "api-metrics.start";

    private ApiMetricsRecorder() {}

    public static void register(Javalin app, TenantApiMetrics metrics) {
        app.before(ctx -> ctx.attribute(ATTR_START, System.nanoTime()));
        app.after(ctx -> {
            Long start = ctx.attribute(ATTR_START);
            if (start == null) return;
            String template = ctx.matchedPath();
            if (template == null || template.isBlank()) template = ctx.path();
            metrics.record(
                    resolveTenant(ctx, template),
                    ctx.method().name(),
                    template,
                    ctx.status().getCode(),
                    System.nanoTime() - start);
        });
    }

    private static String resolveTenant(Context ctx, String template) {
        if (template == null || !template.startsWith("/api/orgs/")) {
            return TenantApiMetrics.GLOBAL_KEY;
        }
        String org = ctx.pathParamMap().get("org");
        return (org == null || org.isBlank()) ? TenantApiMetrics.GLOBAL_KEY : org;
    }
}
