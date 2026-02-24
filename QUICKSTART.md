# QUICKSTART: Building a Sati Tenant Application

Step-by-step guide for building a new tenant application using the `sati_rewrite` library.

## Prerequisites

- **Java 21** (with preview features disabled — just standard Java)
- **Gradle 8.x** (or use the included Gradle wrapper)
- **Gate credentials** — a `.cfg` config file from `operator.tcn.com`

## Step 1: Create a Gradle Project

Create a new directory for your tenant shell and set up `build.gradle`:

```groovy
plugins {
    id 'application'
}

repositories {
    mavenCentral()
    maven {
        url = "https://maven.pkg.github.com/tcncloud/sati"
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
    maven {
        name = 'buf'
        url 'https://buf.build/gen/maven'
    }
}

dependencies {
    implementation 'com.tcn.exile.sati:sati_rewrite:0.1.0-SNAPSHOT'

    // Logging (required — sati uses SLF4J)
    runtimeOnly 'ch.qos.logback:logback-classic:1.5.6'

    // Your JDBC driver (pick one)
    runtimeOnly 'org.postgresql:postgresql:42.7.3'     // PostgreSQL
    // runtimeOnly 'com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11'  // SQL Server
    // runtimeOnly 'com.oracle.database.jdbc:ojdbc11:23.5.0.24.07'    // Oracle
}

application {
    mainClass = 'com.example.Main'
}
```

Add `gradle.properties`:

```properties
version=0.1.0
```

## Step 2: Choose Your Backend Type

Sati supports two backend types:

| Type | When to Use | You Provide |
|------|------------|-------------|
| **JDBC** | Your data lives in a SQL database | Extend `JdbcBackendClient` with your driver + SQL |
| **REST** | Your data lives behind an HTTP API | Use `RestBackendClient` (or extend it) |

Most clients use **JDBC**. The rest of this guide assumes JDBC.

## Step 3: Implement Your Backend Client

Extend `JdbcBackendClient` and implement the abstract methods. Each method provides a SQL query for a specific operation.

```java
package com.example;

import com.tcn.sati.infra.backend.jdbc.JdbcBackendClient;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.tcn.sati.config.SatiConfig;
import com.zaxxer.hikari.HikariConfig;

public class MyBackendClient extends JdbcBackendClient {

    public MyBackendClient(SatiConfig config) {
        super(config);
    }

    @Override
    protected void configureDataSource(HikariConfig hc, BackendConfig cfg) {
        hc.setDriverClassName("org.postgresql.Driver");
        hc.setJdbcUrl(buildJdbcUrl(cfg));
    }

    @Override
    protected String buildJdbcUrl(BackendConfig cfg) {
        // cfg contains host, port, and database name from Gate
        return String.format("jdbc:postgresql://%s:%s/%s",
            cfg.databaseHost, cfg.databasePort, cfg.databaseName);
    }

    // ===== Pool Queries =====

    @Override
    protected String getListPoolsSql() {
        // Must return columns: PoolID, PoolName, Status
        return "SELECT id AS PoolID, name AS PoolName, status AS Status FROM pools";
    }

    @Override
    protected String getPoolStatusSql() {
        // Parameter: ? = poolId (String)
        // Must return columns: PoolID, TotalRecords, AvailableRecords, Status
        return "SELECT id AS PoolID, total AS TotalRecords, available AS AvailableRecords, status AS Status FROM pools WHERE id = ?";
    }

    @Override
    protected String getPoolRecordsSql() {
        // Parameters: ? = pageSize (int), ? = poolId (String), ? = offset (int)
        // Must return columns: RecordID, PoolID, FirstName, LastName, Phone
        return "SELECT r.id AS RecordID, r.pool_id AS PoolID, r.first_name AS FirstName, r.last_name AS LastName, r.phone AS Phone FROM records r WHERE r.pool_id = ?2 LIMIT ?1 OFFSET ?3";
    }

    // ===== Event Queries (called by stored procedure, single JSON ? param) =====

    @Override protected String getTelephonyResultSql()  { return "CALL handle_telephony_result(?)"; }
    @Override protected String getTaskSql()             { return "CALL handle_task(?)"; }
    @Override protected String getAgentCallSql()        { return "CALL handle_agent_call(?)"; }
    @Override protected String getAgentResponseSql()    { return "CALL handle_agent_response(?)"; }
    @Override protected String getTransferInstanceSql() { return "CALL handle_transfer(?)"; }
    @Override protected String getCallRecordingSql()    { return "CALL handle_recording(?)"; }
}
```

### What each SQL method does

| Method | Called When | Input | Expected Output |
|--------|-----------|-------|-----------------|
| `getListPoolsSql()` | Gate requests pool list | None | Rows with `PoolID`, `PoolName`, `Status` |
| `getPoolStatusSql()` | Gate requests pool detail | `?` = poolId | Row with `PoolID`, `TotalRecords`, `AvailableRecords`, `Status` |
| `getPoolRecordsSql()` | Gate requests records | `?1` = pageSize, `?2` = poolId, `?3` = offset | Rows with `RecordID`, `PoolID`, `FirstName`, `LastName`, `Phone` |
| `getTelephonyResultSql()` | Call result event | `?` = JSON payload | Stored procedure |
| `getTaskSql()` | Task event | `?` = JSON payload | Stored procedure |
| `getAgentCallSql()` | Agent call event | `?` = JSON payload | Stored procedure |
| `getAgentResponseSql()` | Agent response event | `?` = JSON payload | Stored procedure |
| `getTransferInstanceSql()` | Transfer event | `?` = JSON payload | Stored procedure |
| `getCallRecordingSql()` | Recording event | `?` = JSON payload | Stored procedure |

## Step 4: Write Main.java

Your entry point is ~30 lines:

```java
package com.example;

import com.tcn.sati.SatiApp;
import com.tcn.sati.config.SatiConfig;
import java.nio.file.*;
import java.util.Base64;

public class Main {
    public static void main(String[] args) throws Exception {
        // 1. Load config from .cfg file (base64-encoded cert bundle)
        Path cfgFile = Path.of("config/sati.cfg");
        SatiConfig config = loadConfig(cfgFile);

        // 2. Create your backend client
        MyBackendClient backend = new MyBackendClient(config);

        // 3. Start Sati
        SatiApp app = SatiApp.builder()
            .config(config)
            .backendClient(backend)
            .appName("My Tenant API")
            .start(8080);

        // 4. Wire up dynamic backend config from Gate
        app.getTenantContext().getGateClient()
            .addConfigListener(backend::onBackendConfigReceived);

        System.out.println("Started! Dashboard: http://localhost:8080/");
        System.out.println("Swagger: http://localhost:8080/swagger");
    }

    private static SatiConfig loadConfig(Path cfgFile) throws Exception {
        // Config file is base64-encoded JSON with certs
        String raw = Files.readString(cfgFile);
        String decoded = new String(Base64.getDecoder().decode(raw.trim()));

        // Parse JSON manually or use Jackson
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(decoded);

        return SatiConfig.builder()
            .apiHostname(json.get("apiHostname").asText())
            .apiPort(json.get("apiPort").asInt())
            .rootCert(json.get("rootCert").asText())
            .publicCert(json.get("publicCert").asText())
            .privateKey(json.get("privateKey").asText())
            .org(json.get("org").asText())
            .tenant(json.get("tenant").asText())
            .build();
    }
}
```

## Step 5: Run

```bash
# Place your .cfg file at config/sati.cfg
./gradlew run
```

On startup you'll see:
1. Gate gRPC connection established
2. Backend configuration received from Gate
3. JDBC connection pool started
4. Job queue connected
5. Event stream connected

Visit `http://localhost:8080/` for the dashboard and `http://localhost:8080/swagger` for the API docs.

## Configuration Reference

### SatiConfig Fields

| Field | Description | Required For |
|-------|-------------|--------------| 
| `apiHostname` | Exile/Gate gRPC host | Gate connection |
| `apiPort` | Exile/Gate gRPC port | Gate connection |
| `rootCert` | CA certificate (PEM) | Gate connection |
| `publicCert` | Client certificate (PEM) | Gate connection |
| `privateKey` | Client private key (PEM) | Gate connection |
| `org` | Organization ID | Gate connection |
| `tenant` | Tenant identifier | All operations |
| `backendUrl` | JDBC URL or REST base URL | Backend operations |
| `backendUser` | DB user or API client ID | Backend operations |
| `backendPassword` | DB password or API secret | Backend operations |

### SatiApp Builder Options

| Option | Default | Description |
|--------|---------|-------------|
| `config()` | required* | Configuration (required for single-tenant) |
| `backendType()` | none | `JDBC` or `REST` (optional if using `backendClient()`) |
| `backendClient()` | none | Custom backend client (required for JDBC) |
| `transferService()` | default | Factory: `GateClient → TransferService` |
| `agentService()` | default | Factory: `GateClient → AgentService` |
| `scrubListService()` | default | Factory: `GateClient → ScrubListService` |
| `skillsService()` | default | Factory: `GateClient → SkillsService` |
| `nclRulesetService()` | default | Factory: `GateClient → NCLRulesetService` |
| `voiceRecordingService()` | default | Factory: `GateClient → VoiceRecordingService` |
| `journeyBufferService()` | default | Factory: `GateClient → JourneyBufferService` |
| `appName()` | auto | Application name for logs/Swagger |
| `multiTenant()` | `false` | Enable multi-tenant mode |
| `tenantDiscovery()` | none | Supplier for tenant discovery |
| `discoveryIntervalSeconds()` | `30` | Tenant discovery interval |

## Optional: Overriding Services

All HTTP route logic lives in **services** (`core/service/`). Each service is a concrete class with `protected final GateClient gate` — subclass it to override any method. Routes are thin HTTP handlers that just parse the request and delegate to the service.

### How It Works

```
Route (HTTP)  →  Service (Business Logic)  →  GateClient (gRPC)
                      ↑
               Your Override
               (subclass)
```

Default services call `GateClient` gRPC methods directly. Your override can add validation, logging, enrichment, or completely replace the logic.

### Available Services

| Service | Methods | Description |
|---------|:-------:|-------------|
| `TransferService` | 5 | Transfer calls, hold/unhold |
| `AgentService` | 13 | Agent CRUD, state, dial, mute, recording |
| `ScrubListService` | 3 | DNC scrub list management |
| `SkillsService` | 4 | Agent skill assignment |
| `NCLRulesetService` | 1 | List NCL ruleset names |
| `VoiceRecordingService` | 4 | Search, download, label recordings |
| `JourneyBufferService` | 1 | Add records to journey buffer |

### Step 1: Subclass the Service

```java
public class MyTransferService extends TransferService {

    public MyTransferService(GateClient gate) {
        super(gate); // GateClient is passed automatically by the factory
    }

    @Override
    public TransferResponse executeTransfer(TransferRequest req) {
        // Custom validation
        if ("restricted".equals(req.getReceivingPartnerAgentId())) {
            return new TransferResponse(false, "Blocked by policy");
        }

        // Call default GateClient implementation
        return super.executeTransfer(req);
    }
}
```

### Step 2: Extend Request/Response Types (Optional)

Service request/response types are regular classes (not records), so you can extend them with tenant-specific fields:

```java
public class MyTransferService extends TransferService {

    // Extended request with extra fields
    public static class MyTransferRequest extends TransferRequest {
        private String accountNumber;
        private String priorityCode;

        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String v) { accountNumber = v; }
        public String getPriorityCode() { return priorityCode; }
        public void setPriorityCode(String v) { priorityCode = v; }
    }

    // Extended response with extra fields
    public static class MyTransferResponse extends TransferResponse {
        private String transferId;

        public MyTransferResponse(boolean success, String message, String transferId) {
            super(success, message);
            this.transferId = transferId;
        }

        public String getTransferId() { return transferId; }
    }

    public MyTransferService(GateClient gate) {
        super(gate);
    }

    @Override
    public TransferResponse executeTransfer(TransferRequest req) {
        // Access extended fields via instanceof
        if (req instanceof MyTransferRequest myReq) {
            log.info("Account: {}, Priority: {}",
                myReq.getAccountNumber(), myReq.getPriorityCode());
        }

        TransferResponse base = super.executeTransfer(req);

        // Return extended response
        return new MyTransferResponse(
            base.isSuccess(), base.getMessage(), "TXF-" + System.currentTimeMillis());
    }
}
```

Jackson automatically serializes the subclass fields in the JSON response.

### Step 3: Wire It Up

Pass a **constructor reference** to the builder. Sati calls it with the real `GateClient` when routes register:

```java
SatiApp.builder()
    .config(loadConfig())
    .backendClient(new MyBackendClient(config))
    .transferService(MyTransferService::new)     // GateClient injected automatically
    .agentService(MyAgentService::new)           // override as many as you need
    .start(8080);
```

Any service you don't override gets the default implementation (calls `GateClient` directly).

> **Why a constructor reference?** The `GateClient` doesn't exist when the builder runs — it's created during `start()`. The factory pattern (`MyService::new` is shorthand for `gate -> new MyService(gate)`) ensures your service is constructed with the real `GateClient` at the right time.

## Optional: Swapping Database Technology

The architecture uses the **Template Method Pattern**, making it easy to swap database providers. Just:

1. Add the new JDBC driver to `build.gradle`
2. Extend `JdbcBackendClient` with your driver config and SQL queries
3. Inject it via `SatiApp.builder().backendClient()`

The test suite includes `JdbcBackendClientTest.java` which demonstrates a complete H2-based implementation of this pattern — see `src/test/java/com/tcn/sati/infra/backend/jdbc/JdbcBackendClientTest.java`.

## Testing Locally

```bash
# Check tenant status
curl http://localhost:8080/api/status

# List pools
curl http://localhost:8080/api/pools

# Test transfer
curl -X POST http://localhost:8080/api/transfer \
     -H "Content-Type: application/json" \
     -d '{"partnerAgentId":"agent-123", "kind":"COLD", "action":"START", "receivingPartnerAgentId":"agent-456"}'
```
```
