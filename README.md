# Sati SDK — Integration Guide

**Sati** is a Java SDK that connects your application to the **Exile** platform via the **Gate** gRPC gateway. It provides everything you need out of the box — an HTTP server, admin dashboard, bidirectional streaming, connection pooling, and dynamic configuration — so you can focus entirely on wiring up your own backend data source.

Your application is a **thin shell**: a small Java project that loads configuration, implements a backend adapter for your database or API, and delegates everything else to Sati.

---

## How It Works

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Your Application (Thin Shell)                  │
│                                                                      │
│   Main.java              YourBackendClient.java                      │
│   - Load config           - JDBC driver / REST client                │
│   - Start SatiApp         - SQL statements or API calls              │
│   - Wire config listener  - Event handlers                           │
│                                                                      │
│ ──────────────────────────────────────────────────────────────────── │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐   │
│   │                        Sati SDK (Library)                    │   │
│   │                                                              │   │
│   │  SatiApp Builder     │  Javalin HTTP + Swagger UI            │   │
│   │  GateClient (gRPC)   │  Admin Dashboard + Health Checks      │   │
│   │  JobQueueStream      │  HikariCP Connection Pooling          │   │
│   │  EventStreamClient   │  Dynamic Config from Gate             │   │
│   └──────────────────────────────────────────────────────────────┘   │
│                             │                                        │
└─────────────────────────────┼────────────────────────────────────────┘
                              │  gRPC (mTLS)
                              ▼
                    ┌───────────────────┐
                    │   Exile / Gate    │
                    │   (TCN Platform)  │
                    └───────────────────┘
```

**You write:** `Main.java` + a backend client class + optionally custom services.
**Sati handles:** gRPC connectivity, certificate auth, streaming, HTTP routes, health checks, Swagger, admin UI, and job processing.

---

## What You Get From Sati

| Feature | Description |
|---------|-------------|
| **Javalin HTTP Server** | REST API with OpenAPI/Swagger documentation — auto-registered routes for pools, agents, transfers, admin |
| **gRPC Gate Integration** | Secure mTLS connection to Exile services with auto-reconnect |
| **Bidirectional Streaming** | Real-time job queue and event stream with acknowledgment support |
| **Virtual Threads** | Native Java 21 Virtual Threads throughout the entire SDK for maximum concurrency and minimal blocking footprint |
| **Dynamic Configuration** | Database credentials delivered from Gate at runtime (no hardcoded secrets) |
| **Connection Pooling** | HikariCP for JDBC backends with async initialization |
| **Admin Dashboard** | Built-in status monitoring, log viewing, and config management |
| **Hot Reload** | Gate certificates can be rotated without restarting HTTP |

---

## Getting Started

### Prerequisites

- **Java 21+**
- **Gradle 8+**
- A **Gate configuration file** (`.cfg`) — provided during onboarding. This contains the mTLS certificates needed to connect to Exile.

### Step 1 — Create Your Project

Create a new Gradle project with the following structure:

```
my-exile-app/
├── build.gradle
├── settings.gradle
├── src/main/java/com/mycompany/exile/
│   ├── Main.java                    # Entry point
│   └── MyBackendClient.java         # Your backend adapter
└── workdir/config/
    └── com.tcn.exiles.sati.config.cfg   # Gate config (from onboarding)
```

### Step 2 — Configure Dependencies

**settings.gradle:**
```groovy
rootProject.name = 'my-exile-app'

// Include Sati as a sibling project (during development)
includeBuild '../sati_rewrite'
```

**build.gradle:**
```groovy
plugins {
    id 'application'
}

repositories {
    mavenCentral()
    maven {
        name = 'buf'
        url 'https://buf.build/gen/maven'
    }
}

dependencies {
    // Sati SDK (all Javalin, gRPC, Swagger, and Exile API deps come transitively)
    implementation project(':sati_rewrite')
    
    // Or if published to a repository:
    // implementation 'com.tcn:sati-sdk:1.0.0'
    
    // Logging — Sati uses SLF4J, you must provide a runtime implementation
    implementation 'ch.qos.logback:logback-classic:1.5.6'
    
    // JSON — for parsing the .cfg config file in Main.java
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'
    
    // Database — HikariCP is needed if extending JdbcBackendClient
    //            (it appears in the method signatures you override)
    implementation 'com.zaxxer:HikariCP:5.1.0'
    
    // YOUR database driver — examples:
    // implementation 'org.postgresql:postgresql:42.7.3'
    // implementation 'com.mysql:mysql-connector-j:8.3.0'
    // implementation files('libs/your-proprietary-driver.jar')
}

application {
    mainClass = 'com.mycompany.exile.Main'
}
```

### Step 3 — Write Your Entry Point

```java
package com.mycompany.exile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.SatiApp;
import com.tcn.sati.config.SatiConfig;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class Main {

    public static void main(String[] args) {
        // 1. Load the Gate config file (.cfg)
        SatiConfig config = loadGateConfig("workdir/config/com.tcn.exiles.sati.config.cfg");
        
        // 2. Create your backend client
        MyBackendClient backend = new MyBackendClient(config);
        
        // 3. Start the application
        SatiApp app = SatiApp.builder()
                .config(config)
                .backendClient(backend)
                .appName("My Exile App")
                .start(8080);
        
        // 4. Wire up dynamic configuration from Gate
        //    Gate will push database credentials at runtime
        app.getTenantContext().getGateClient().setConfigListener(backend::onBackendConfigReceived);
        app.getTenantContext().getGateClient().startConfigPolling();
        
        System.out.println("Application started on port 8080");
    }

    // --- Config loading ---
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GateConfig {
        @JsonProperty("ca_certificate")   public String caCertificate;
        @JsonProperty("certificate")       public String certificate;
        @JsonProperty("private_key")       public String privateKey;
        @JsonProperty("api_endpoint")      public String apiEndpoint;
    }

    static SatiConfig loadGateConfig(String path) {
        try {
            // Config file is Base64-encoded JSON
            String encoded = Files.readString(Path.of(path)).trim().replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(encoded);

            ObjectMapper mapper = new ObjectMapper();
            GateConfig gc = mapper.readValue(decoded, GateConfig.class);

            URI uri = URI.create(gc.apiEndpoint);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 443 : uri.getPort();

            // Organization is extracted from the certificate
            String org = SatiConfig.extractOrgFromCert(gc.certificate);

            return SatiConfig.builder()
                    .apiHostname(host)
                    .apiPort(port)
                    .rootCert(gc.caCertificate)
                    .publicCert(gc.certificate)
                    .privateKey(gc.privateKey)
                    .org(org)
                    .tenant(org)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Gate config: " + e.getMessage(), e);
        }
    }
}
```

### Step 4 — Implement Your Backend Client

Your backend client connects Sati to **your** data source. For JDBC databases, extend `JdbcBackendClient`:

```java
package com.mycompany.exile;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.jdbc.JdbcBackendClient;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.zaxxer.hikari.HikariConfig;

public class MyBackendClient extends JdbcBackendClient {

    public MyBackendClient(SatiConfig config) {
        super(config);
    }

    // --- JDBC Driver Setup ---

    @Override
    protected void configureDataSource(HikariConfig hc, BackendConfig cfg) {
        hc.setDriverClassName("org.postgresql.Driver");  // Your driver
        hc.setJdbcUrl(buildJdbcUrl(cfg));
    }

    @Override
    protected String buildJdbcUrl(BackendConfig cfg) {
        return String.format("jdbc:postgresql://%s:%s/%s",
                cfg.databaseHost, cfg.databasePort, cfg.databaseName);
    }

    // --- SQL for Pool Operations ---
    //     These are called by Sati's built-in routes

    @Override
    protected String getListPoolsSql() {
        return "SELECT id AS \"PoolID\", name AS \"PoolName\", status AS \"Status\" FROM pools";
    }

    @Override
    protected String getPoolStatusSql() {
        // Use ? as the placeholder for the pool ID parameter
        return "SELECT id AS \"PoolID\", total AS \"TotalRecords\", " +
               "available AS \"AvailableRecords\", status AS \"Status\" " +
               "FROM pools WHERE id = ?";
    }

    @Override
    protected String getPoolRecordsSql() {
        // Parameters: pool_id, limit, offset
        return "SELECT id AS \"RecordID\", pool_id AS \"PoolID\", " +
               "first_name AS \"FirstName\", last_name AS \"LastName\", phone AS \"Phone\" " +
               "FROM records WHERE pool_id = ? ORDER BY id LIMIT ? OFFSET ?";
    }

    // --- SQL for Event Handlers ---
    //     These are called when Gate pushes events from Exile

    @Override
    protected String getTelephonyResultSql() {
        return "SELECT process_telephony_result(?::jsonb)";
    }

    @Override
    protected String getTaskSql() {
        return "SELECT process_task(?::jsonb)";
    }

    @Override
    protected String getAgentCallSql() {
        return "SELECT process_agent_call(?::jsonb)";
    }

    @Override
    protected String getAgentResponseSql() {
        return "SELECT process_agent_response(?::jsonb)";
    }

    @Override
    protected String getTransferInstanceSql() {
        return "SELECT process_transfer(?::jsonb)";
    }

    @Override
    protected String getCallRecordingSql() {
        return "SELECT process_recording(?::jsonb)";
    }
}
```

> **Note on column aliases:** Pool operation SQL must return columns with the exact alias names shown above (`PoolID`, `PoolName`, `Status`, etc.). Sati maps these to its internal DTOs.

> **Note on event handler SQL:** Event handler methods receive a JSON string parameter (`?`). Your stored procedure or function should accept this JSON and process it according to your schema.

---

## Configuration

### The Gate Config File (`.cfg`)

This file is provided during Exile onboarding. It's a **Base64-encoded JSON** file containing your mTLS certificates for connecting to Gate:

```json
{
  "ca_certificate": "-----BEGIN CERTIFICATE-----\n...",
  "certificate": "-----BEGIN CERTIFICATE-----\n...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...",
  "api_endpoint": "https://gate.example.com:443"
}
```

**Search paths** (in priority order):
1. `/workdir/config/com.tcn.exiles.sati.config.cfg` — absolute, for Docker
2. `workdir/config/com.tcn.exiles.sati.config.cfg` — relative, for local dev

### Dynamic Backend Configuration

You do **not** hardcode database credentials. Gate delivers them at runtime via gRPC polling:

| Field | Description |
|-------|-------------|
| `database_host` | Database hostname |
| `database_port` | Database port |
| `database_name` | Database/schema name |
| `database_type` | e.g., `"IRIS"`, `"Cache"`, `"MySQL"`, `"PostgreSQL"` |
| `database_username` | DB username |
| `database_password` | DB password |
| `database_url` | Complete JDBC URL (optional, overrides host/port/name) |
| `use_tls` | Enable TLS for DB connection |
| `max_number_connections` | Connection pool size |

When Gate pushes updated credentials, the `onBackendConfigReceived` callback is invoked on your backend client. `JdbcBackendClient` handles this automatically — it tears down the old connection pool and creates a new one (atomic swap, zero downtime).

### SatiConfig Builder (Manual Configuration)

For local development without a `.cfg` file, you can build config manually:

```java
SatiConfig config = SatiConfig.builder()
    .apiHostname("gate.example.com")
    .apiPort(443)
    .rootCert(caCertPem)
    .publicCert(clientCertPem)
    .privateKey(clientKeyPem)
    .org("my-organization")
    .tenant("my-tenant-id")
    .build();
```

---

## Backend Client Options

### Option A: JDBC Backend (Recommended for databases)

Extend `JdbcBackendClient` — this is the most common pattern. You get:
- **HikariCP connection pooling** with configurable sizes
- **Async initialization** — app starts even if the database isn't ready yet
- **Dynamic reconfiguration** — handles credential rotation from Gate
- **Health monitoring** — tracks connection state

You implement these abstract methods:

| Method | Purpose |
|--------|---------|
| `configureDataSource()` | Set JDBC driver class and connection properties |
| `buildJdbcUrl()` | Build a JDBC URL from the `BackendConfig` components |
| `getListPoolsSql()` | SQL to list pools |
| `getPoolStatusSql()` | SQL to get a single pool's status (1 parameter: pool ID) |
| `getPoolRecordsSql()` | SQL for paginated records (3 parameters: pool ID, limit, offset) |
| `getTelephonyResultSql()` | SQL/stored procedure for telephony results (1 JSON parameter) |
| `getTaskSql()` | SQL/stored procedure for tasks (1 JSON parameter) |
| `getAgentCallSql()` | SQL/stored procedure for agent calls (1 JSON parameter) |
| `getAgentResponseSql()` | SQL/stored procedure for agent responses (1 JSON parameter) |
| `getTransferInstanceSql()` | SQL/stored procedure for transfers (1 JSON parameter) |
| `getCallRecordingSql()` | SQL/stored procedure for recordings (1 JSON parameter) |
| `getPopAccountSql()` | SQL/stored procedure to screen-pop an account (1 JSON parameter) |
| `getSearchRecordsSql()` | SQL/stored procedure to search records (1 JSON parameter) |
| `getReadFieldsSql()` | SQL/stored procedure to read record fields (1 JSON parameter) |
| `getWriteFieldsSql()` | SQL/stored procedure to write record fields (1 JSON parameter) |
| `getCreatePaymentSql()` | SQL/stored procedure to create a payment (1 JSON parameter) |
| `getExecuteLogicSql()` | SQL/stored procedure to execute custom logic blocks (1 JSON parameter) |

### Option B: REST Backend

If your data source is a REST API rather than a database, use the built-in `RestBackendClient`:

```java
SatiApp.builder()
    .config(config)
    .backendType(BackendType.REST)
    .appName("My REST App")
    .start(8080);
```

### Option C: Custom Backend

For full control, implement `TenantBackendClient` directly:

```java
public class MyCustomBackend implements TenantBackendClient {

    @Override public List<PoolInfo> listPools() { /* ... */ }
    @Override public PoolStatus getPoolStatus(String poolId) { /* ... */ }
    @Override public List<PoolRecord> getPoolRecords(String poolId, int page) { /* ... */ }
    
    @Override public void handleTelephonyResult(TelephonyResult result) { /* ... */ }
    @Override public void handleTask(ExileTask task) { /* ... */ }
    @Override public void handleAgentCall(AgentCall call) { /* ... */ }
    @Override public void handleAgentResponse(AgentResponse response) { /* ... */ }
    @Override public void handleTransferInstance(TransferInstance transfer) { /* ... */ }
    @Override public void handleCallRecording(CallRecording recording) { /* ... */ }
    
    @Override public boolean isConnected() { /* ... */ }
    @Override public void close() { /* ... */ }
}
```

---

## Extending Services

Sati provides default implementations for transfer and agent operations. Override them to add custom business logic:

```java
public class MyTransferService extends TransferService {

    // Add custom fields to the request DTO
    public static class MyTransferRequest extends TransferRequest {
        private String accountNumber;
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    }

    @Override
    public TransferResponse executeTransfer(TransferRequest req) {
        // Custom validation
        if (req.getTargetQueue().equalsIgnoreCase("restricted")) {
            return new TransferResponse(false, "Transfers to restricted queue are blocked.");
        }

        // Access extended fields if present
        if (req instanceof MyTransferRequest myReq) {
            log.info("Processing account: {}", myReq.getAccountNumber());
        }

        // Delegate to base implementation
        return super.executeTransfer(req);
    }
}
```

Register it with the builder:

```java
SatiApp.builder()
    .config(config)
    .backendClient(backend)
    .transferService(new MyTransferService())    // Custom transfer logic
    .agentService(new MyAgentService())          // Custom agent logic
    .appName("My App")
    .start(8080);
```

---

## Config File Hot-Reload (Optional)

For production deployments where Gate certificates may rotate, implement a file watcher to trigger reconnection without restarting the HTTP server:

```java
// Watch for changes to the .cfg file
ConfigWatcher watcher = new ConfigWatcher(configPath, newConfig -> {
    app.getTenantContext().reconnectGate(newConfig);
});
watcher.start();
```

This performs a **surgical reconnection** — only the gRPC layer (GateClient, JobStream) reconnects. The HTTP server stays up throughout.

---

## HTTP Endpoints (Provided by Sati)

These routes are auto-registered by the SDK. You do not need to implement them.

### Admin

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Redirect to admin dashboard |
| `/swagger` | GET | Swagger UI |
| `/swagger-docs` | GET | OpenAPI JSON spec |
| `/api/admin/status` | GET | System status (connections, jobs, events) |
| `/api/admin/logs` | GET | Recent log messages |
| `/api/admin/config` | POST | Load new configuration |

### Pools (Backend)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/pools` | GET | List all pools |
| `/api/pools/{id}/status` | GET | Get pool status |
| `/api/pools/{id}/records` | GET | Get pool records (paginated) |

### Gate

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/gate/status` | GET | Gate connection status |

---

## Health Checks

```java
TenantStatus status = app.getTenantContext().getStatus();
// Fields: tenantKey, running, gateConnected, backendConnected,
//         jobQueueConnected, eventStreamRunning, processedJobs,
//         failedJobs, processedEvents, createdAt
```

Or hit the endpoint: `GET /api/admin/status`

---

## Deployment

### Docker

Example `Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/my-exile-app.jar app.jar
COPY workdir /workdir

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

### Kubernetes

- **Port:** 8080 (configurable)
- **Liveness probe:** `GET /api/admin/status`
- **Config:** Mount the `.cfg` file at `/workdir/config/com.tcn.exiles.sati.config.cfg`

---

## Reference: Demo Application

The `demo/` folder in this repository is a standalone, template application demonstrating how to integrate the Sati SDK. It consists of just **5 files**:

| File | Purpose |
|------|---------|
| `Main.java` | Loads `.cfg`, builds `SatiConfig`, starts `SatiApp`, wires config listener + file watcher |
| `AppBackendClient.java` | Extends `JdbcBackendClient` for PostgreSQL — driver config + stored procedure SQL stubs |
| `CustomAgentService.java` | Example of overriding a default Sati service with custom logic |
| `ConfigWatcher.java` | Watches the `.cfg` file for certificate rotation and triggers `reconnectGate()` |
| `MultiTenantMain.java` | Alternate entry point demonstrating multi-tenant mode |

Copy this folder to a new location to start building your own integration.

---

## SatiApp Builder Reference

| Method | Description |
|--------|-------------|
| `.config(SatiConfig)` | Gate connection config (required) |
| `.backendClient(TenantBackendClient)` | Your custom backend implementation |
| `.backendType(BackendType)` | `JDBC` or `REST` — used when _not_ providing a custom client |
| `.transferService(TransferService)` | Override default transfer logic |
| `.agentService(AgentService)` | Override default agent logic |
| `.appName(String)` | Application name (shown in Swagger UI) |
| `.multiTenant(boolean)` | Enable multi-tenant mode |
| `.tenantDiscovery(Supplier)` | Auto-discover tenants (multi-tenant only) |
| `.discoveryIntervalSeconds(long)` | Discovery poll interval (default: 30s) |
| `.start(int port)` | Build and start on specified port |

---

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Javalin | 6.7.0 | HTTP server |
| gRPC | 1.68.1 | Gate communication |
| HikariCP | 5.1.0 | Connection pooling |
| Jackson | 2.17.0 | JSON serialization |
| Bouncy Castle | 1.78.1 | PKCS#1 key support |
| SLF4J | 2.0.12 | Logging API |
| Exile API | * | Protobuf definitions (from Buf) |

> *Exile API versions are pinned in `gradle.properties`.*

Your application must provide a **logging implementation** (Sati uses SLF4J). We recommend Logback:

```groovy
implementation 'ch.qos.logback:logback-classic:1.5.6'
```

---

## Project Structure

```
sati_rewrite/
├── src/main/java/com/tcn/sati/
│   ├── SatiApp.java                        # Entry point + builder
│   ├── config/
│   │   ├── SatiConfig.java                 # Configuration record
│   │   └── BackendType.java                # JDBC or REST enum
│   ├── core/
│   │   ├── job/
│   │   │   └── JobProcessor.java           # Processes jobs from Gate
│   │   ├── route/
│   │   │   ├── AdminRoutes.java            # /api/admin/* endpoints
│   │   │   ├── BackendRoutes.java          # /api/pools/* endpoints
│   │   │   ├── GateRoutes.java             # /api/gate/* endpoints
│   │   │   ├── TransferRoutes.java         # /api/transfer/* endpoints
│   │   │   └── AgentRoutes.java            # /api/agent/* endpoints
│   │   ├── service/
│   │   │   ├── TransferService.java        # Extensible transfer logic
│   │   │   └── AgentService.java           # Extensible agent logic
│   │   └── tenant/
│   │       ├── TenantContext.java          # Per-tenant resource bundle
│   │       └── TenantManager.java          # Multi-tenant management
│   └── infra/
│       ├── backend/
│       │   ├── TenantBackendClient.java    # Interface you implement
│       │   ├── jdbc/
│       │   │   └── JdbcBackendClient.java  # Abstract JDBC base class
│       │   └── rest/
│       │       └── RestBackendClient.java  # REST API implementation
│       ├── gate/
│       │   ├── GateClient.java             # gRPC connection to Gate
│       │   ├── EventStreamClient.java      # Bidirectional event stream
│       │   └── JobQueueClient.java         # Bidirectional job queue
│       └── logging/
│           └── MemoryLogAppender.java      # In-memory logs for dashboard
└── src/main/resources/
    └── public/                             # Admin dashboard static files
```
