# Architecture

Sati is a Java 21 client library for TCN's exile gate service, speaking the v3
protocol over a single bidirectional gRPC stream. This document covers the runtime
model, module layout, v3 work-stream flow, and telemetry plumbing.

## Design goals

- **Plain Java, no framework.** The library has no Micronaut / Spring / DI container.
  Integrations construct an `ExileClient` (or `ExileClientManager`) with a builder
  and hand in a `Plugin`.
- **Protos never escape the library.** Handlers receive plain Java records
  (`Pool`, `DataRecord`, `AgentCallEvent`, …). All conversion happens at the stream
  boundary in `ProtoConverter`.
- **One persistent stream, many jobs/events.** The v3 protocol multiplexes all
  job dispatch, event delivery, heartbeats, lease extensions, and acks over one
  `workStream` RPC. Reconnect logic is centralised in `WorkStreamClient`.
- **Virtual threads for handlers.** Each incoming `WorkItem` is dispatched on a
  virtual thread, so blocking JDBC/HTTP inside a `Plugin` is fine.
- **Observability built in.** OpenTelemetry traces follow the work item across
  the boundary; metrics and logs are exported back to the gate via the
  `TelemetryService`.

## Module layout

```
core           library — ExileClient, services, WorkStream, OTel exporters
 ├─ depends on logback-ext
config         config-file + multi-tenant lifecycle helpers
 └─ depends on core
logback-ext    in-memory logback appender (LogShipper + MemoryAppender)
demo           reference single-tenant app
 └─ depends on core + config + logback-ext
```

The `core` module exposes the gate API as a set of plain-Java domain services
(`AgentService`, `CallService`, `RecordingService`, `ScrubListService`,
`ConfigService`, `JourneyService`, `TelemetryService`) plus the `WorkStreamClient`
that drives the push side. Stubs come from the pre-built `buf.build/gen/maven`
artefacts — no local protoc.

## Runtime model

### ExileClient

`ExileClient` is the core entry point. On `start()` it does **not** open the work
stream immediately. Instead it:

1. Starts a config poller (`ConfigService.getClientConfiguration`) on a
   single-thread scheduled executor. Poll interval defaults to 10 s.
2. On each poll, if the config differs from the previous one, calls
   `Plugin.onConfig(...)`. The plugin validates the gate-supplied payload and
   initialises its own resources (DB pools, HTTP clients, …).
3. Opens the `WorkStream` only after `onConfig` returns `true`. Until then, the
   plugin is considered "not ready" and the gate never sees the client as live.

This gates stream admission on the plugin's readiness and means a mis-configured
integration simply retries instead of flapping.

Shared infrastructure (`serviceChannel`, `MetricsManager`, `GrpcLogShipper`) is
constructed lazily: the metrics manager is created after the first config poll
because it needs `org_id` and the certificate name for OTel resource attributes.

### Plugin / PluginBase

`Plugin` is the single integration surface. It extends `JobHandler` and
`EventHandler` and adds a lifecycle callback:

- `JobHandler` — request/response RPCs: `listPools`, `getPoolStatus`,
  `getPoolRecords`, `searchRecords`, `getRecordFields`, `setRecordFields`,
  `createPayment`, `popAccount`, `executeLogic`, `info`, `diagnostics`,
  `listTenantLogs`, `setLogLevel`, `shutdown`, `processLog`.
- `EventHandler` — fire-and-acknowledge events: `onAgentCall`,
  `onTelephonyResult`, `onAgentResponse`, `onTransferInstance`,
  `onCallRecording`, `onTask`.
- `onConfig(ClientConfiguration)` — called before the stream opens and on every
  config change. Returning `true` gates stream admission.

All `JobHandler` methods default to throwing `UnsupportedOperationException`;
integrations override only the jobs they implement and declare those as
capabilities when registering. All `EventHandler` methods default to no-ops.

`PluginBase` is the recommended starting point — it provides working defaults
for the cross-cutting operations (`listTenantLogs`, `setLogLevel`, `info`,
`diagnostics`, `shutdown`, `processLog`) so an integration only needs to
implement `onConfig`, the relevant job methods, and the relevant event methods.

### WorkStreamClient (internal)

`com.tcn.exile.internal.WorkStreamClient` implements the v3 protocol over a
single `workStream` RPC. Lifecycle:

1. **Channel.** A shared `ManagedChannel` is created via `ChannelFactory` (mTLS
   from `ExileConfig`: root cert, public cert, PKCS#1/PKCS#8 private key, host,
   port). The channel is reused across reconnects; it is only shut down on
   `close()`.
2. **Reconnect loop.** A single platform thread (`exile-work-stream`) runs a
   reconnect loop driven by `Backoff`. RST_STREAM NO_ERROR from envoy is
   treated as a graceful close and resets the backoff; all other failures
   record a failure.
3. **Register.** On each connection, the client sends
   `Register{client_name, client_version, capabilities}` and waits for
   `Registered{client_id, heartbeat_interval, default_lease, max_inflight}`.
4. **Pull.** After registration, a single `Pull{max_items=INT_MAX}` is sent.
   The gate pushes `WorkItem`s continuously; HTTP/2 flow control handles
   backpressure.
5. **Dispatch.** Each `WorkItem` is handed to a virtual-thread
   `workerPool`. `WORK_CATEGORY_JOB` items → `dispatchJob` builds a `Result`
   and sends it back. `WORK_CATEGORY_EVENT` items → `dispatchEvent` invokes
   the handler and sends `Ack{work_ids=[id]}`. Handler failures produce
   `Result{error=...}` for jobs and `Nack{reason=...}` for events.
6. **Keepalive.** `Heartbeat` responses echo with `client_time`. `LeaseExpiring`
   triggers `ExtendLease{+300s}` automatically.
7. **Result round-trip.** `ResultAccepted` clears the per-work span context.
   For events, the span context is cleared immediately after ack.

Sending is serialised with a `synchronized (observer)` block; on send failure
the observer is detached and `onError` is forced so the reconnect loop picks up
immediately instead of waiting for the next recv to fail.

### ProtoConverter

`ProtoConverter` is the bidirectional boundary between proto types (from
`build.buf.gen.tcnapi.exile.gate.v3`) and the library's domain records in
`com.tcn.exile.model` and `com.tcn.exile.model.event`. It also handles
`google.protobuf.Struct` ↔ `Map<String, Object>` and `Timestamp`/`Duration` ↔
`Instant`/`Duration`. Nothing outside `core.internal` depends on proto types.

## Configuration lifecycle

### ExileConfig

Immutable record of mTLS credentials + endpoint. `org()` is derived lazily from
the certificate's `O=` subject field; the gate always keys by organisation, so
the library does the same.

### ConfigParser (config module)

Parses the Base64-encoded JSON config file (`com.tcn.exiles.sati.config.cfg`).
Fields: `ca_certificate`, `certificate`, `private_key`, `api_endpoint`
(`host:port` or URL), optional `certificate_name`. Uses a small hand-written
JSON parser to avoid pulling Jackson/Gson into the library.

### ConfigFileWatcher (config module)

Watches one or more directories (defaults: `/workdir/config`,
`workdir/config`) via
[methvin/directory-watcher](https://github.com/gmethvin/directory-watcher). On
CREATE/MODIFY for the known filename it parses the file and invokes
`onConfigChanged(ExileConfig)`; on DELETE it invokes `onConfigRemoved()`. Also
exposes `writeConfig(String)` so the certificate rotator can persist a rotated
credential, which then naturally triggers the watcher again.

### ExileClientManager (config module)

Single-tenant convenience wrapper: wires a `ConfigFileWatcher` to
`ExileClient` creation/destruction and schedules `CertificateRotator` checks
(hourly by default). When `onConfigChanged` fires with a new org, the old
client is destroyed and a new one is built. Returns the current `ExileClient`
and a `StreamStatus` snapshot for health endpoints.

### MultiTenantManager (config module)

Multi-tenant reconciliation loop. The caller supplies a
`Supplier<Map<String, ExileConfig>>` (the desired tenant set) and a
`Function<ExileConfig, ExileClient>` factory. On each tick the manager:

- Destroys clients for tenants no longer in the desired map.
- Creates clients for new tenants (and calls `start()` on them).
- Recreates a client if the config's `org()` changed.

Used by integrations that discover tenants from an upstream API (e.g. Velosidy)
rather than from a local config file.

## Observability

### OpenTelemetry tracing

`WorkStreamClient` creates an `exile.work.<job|event>` consumer span for every
incoming `WorkItem`. If the gate attaches a W3C `trace_parent`, it is parsed via
`parseTraceParent` and linked as the span's parent so upstream traces stitch
together.

The span's context is stashed in `workSpanContexts` keyed by `work_id` so that
asynchronous server responses (`ResultAccepted`, `LeaseExpiring`, `Error`) can
log within the same trace via `withWorkSpan`.

`traceId` / `spanId` are pushed into SLF4J MDC for the duration of the handler
and wired into `MemoryAppender` via a `TraceContextExtractor` so every emitted
log event carries trace context.

### Metrics

`MetricsManager` owns the OTel `MeterProvider` and a custom
`GrpcMetricExporter` that pushes metric data to the gate's `TelemetryService`.
Built-in instruments:

- Work-item duration (histogram)
- Per-method duration + success (labelled histogram)
- Reconnect duration (histogram)
- Stream phase / inflight (gauges from `WorkStreamClient.status()`)

Plugins can register their own instruments via `ExileClient.meter()`. The meter
is `null` until the first config poll completes (resource attributes require
`org_id`).

### Logs

`logback-ext` provides `MemoryAppender`, a bounded in-memory appender
(1000-event ring, 1-hour TTL). It serves two purposes:

- **`listTenantLogs` / `setLogLevel` jobs.** `PluginBase` implements these by
  reading/filtering the in-memory buffer directly.
- **Structured log shipping.** `GrpcLogShipper` drains the appender on a
  background thread and forwards events to `TelemetryService`, attaching the
  MDC trace context captured at append time.

## Demo application

The `demo` module is a minimal, runnable integration:

- `Main` constructs an `ExileClientManager` with `DemoPlugin` and starts a
  `StatusServer` on `PORT` (default 8080).
- `DemoPlugin` extends `PluginBase`, logs every event, and returns canned job
  responses.
- `StatusServer` uses the JDK `com.sun.net.httpserver.HttpServer` with a
  virtual-thread executor to serve `/health` and `/status`.

It demonstrates the shape of a real integration without any framework dependency.

## Threading summary

| Thread | Purpose |
| --- | --- |
| `exile-config-poller` | Single-thread scheduled executor, polls `ConfigService.getClientConfiguration`. |
| `exile-work-stream` | Single platform thread running the reconnect loop. |
| `workerPool` | `Executors.newVirtualThreadPerTaskExecutor()` — one virtual thread per work item. |
| `exile-cert-rotator` | Single-thread scheduled executor in `ExileClientManager`. |
| `exile-tenant-poller` | Single-thread scheduled executor in `MultiTenantManager`. |
| `exile-shutdown` | Virtual thread used by `PluginBase.shutdown` to let the reply flush before `System.exit`. |
| Log shipper thread | Drains `MemoryAppender` into `TelemetryService`. |

All long-lived threads are daemons so they do not block JVM exit.
