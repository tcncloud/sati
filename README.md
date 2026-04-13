# sati

Plain Java client library for TCN's exile gate service (v3 protocol).

Sati connects to the gate over a single bidirectional gRPC stream, receives jobs and
events from the gate, dispatches them to a user-supplied `Plugin`, and streams
results, acks, metrics, and logs back. It is a library first — the `demo` module is a
minimal reference application built on top of it.

## Modules

| Module | Purpose |
| --- | --- |
| `core` | The client library. `ExileClient`, `ExileConfig`, `Plugin`/`PluginBase`, domain services (agents, calls, recordings, scrub lists, config, journey, telemetry), the v3 `WorkStream` implementation, OpenTelemetry metrics and tracing. |
| `config` | Config-file watching and client lifecycle helpers. `ConfigParser` reads the standard Base64-encoded JSON config, `ConfigFileWatcher` reacts to changes on disk, `ExileClientManager` drives a single-tenant client, and `MultiTenantManager` reconciles a fleet of clients against a tenant provider. |
| `logback-ext` | In-memory logback appender (`MemoryAppender`) used by `core` to serve `listTenantLogs` and ship structured logs back to the gate via the telemetry service. |
| `demo` | Reference single-tenant integration: watches a config file, wires up a stub `Plugin`, exposes `/health` and `/status` over plain `HttpServer`. |

Build graph: `core` depends on `logback-ext`; `config` depends on `core`; `demo`
depends on all three.

## Requirements

- Java 21 (uses virtual threads, records, switch patterns)
- Gradle 8+ (wrapper included)
- exileapi v3 protobuf + gRPC stubs from the `buf.build/gen/maven` repository (versions
  pinned in `gradle.properties`)

## Quick start

Integrations embed `core` (and optionally `config`) as dependencies, implement a
`Plugin`, and either drive `ExileClient` directly or let `ExileClientManager` watch a
config file for them.

```java
// 1. Implement the Plugin (extend PluginBase for sensible defaults).
public class MyPlugin extends PluginBase {
    @Override
    public boolean onConfig(ConfigService.ClientConfiguration config) {
        // Validate the gate-supplied payload, open DB pools, etc.
        return true; // WorkStream opens only after this returns true.
    }

    @Override
    public List<Pool> listPools() { ... }

    @Override
    public void onAgentCall(AgentCallEvent event) { ... }
}

// 2. Let ExileClientManager own the lifecycle.
var manager = ExileClientManager.builder()
    .clientName("my-integration")
    .clientVersion("1.0.0")
    .maxConcurrency(5)
    .plugin(new MyPlugin())
    .build();
manager.start();
```

See `demo/src/main/java/com/tcn/exile/demo` for a runnable example.

## Running the demo

```bash
# 1. Drop a Base64-encoded JSON config at the default location:
mkdir -p workdir/config
echo "$BASE64_CONFIG" > workdir/config/com.tcn.exiles.sati.config.cfg

# 2. Run the demo:
env EXILE_CONFIG_DIR=./workdir/config /gradlew :demo:run

# Or build a shadow jar and run it standalone:
./gradlew :demo:shadowJar
env EXILE_CONFIG_DIR=./workdir/config java -jar demo/build/libs/demo-all.jar

# 3. Health/status:
curl http://localhost:8080/health
curl http://localhost:8080/status
```

Environment variables the demo reads: `PORT` (default `8080`), `CONFIG_DIR` (default
watches `/workdir/config` and `workdir/config`).

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the runtime model, module layout, v3
protocol flow, and telemetry plumbing.

## License

```text
Copyright 2017-2026 original authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
