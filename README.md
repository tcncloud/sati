# Sati SDK

**Sati** is a Java SDK for building tenant applications that integrate with the Exile/Gate ecosystem. It follows a **Thin Shell** architecture where consuming applications are minimal wrappers that inject tenant-specific configuration and database implementations.

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                         Consumer Application                           │
│   ┌─────────────────┐  ┌────────────────────────────────────────────┐  │
│   │  Main.java      │  │  Custom Backend Client                     │  │
│   │  (Bootstrap)    │  │  (e.g., CustomBackendClient)               │  │
│   │  - Load config  │  │  - JDBC driver config                      │  │
│   │  - Start app    │  │  - SQL statements                          │  │
│   └─────────────────┘  └────────────────────────────────────────────┘  │
│                           │                   │                        │
│ ──────────────────────────┼───────────────────┼─────────────────────── │
│                           ▼                   ▼                        │
│   ┌───────────────────────────────────────────────────────────────┐    │
│   │                          Sati (SDK)                           │    │
│   │                                                               │    │
│   │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐    │    │
│   │  │  SatiApp    │  │ SatiConfig  │  │ TenantBackendClient │    │    │
│   │  │  Builder    │  │  (Record)   │  │    (Interface)      │    │    │
│   │  └─────────────┘  └─────────────┘  └─────────────────────┘    │    │
│   │                                              │                │    │
│   │  ┌─────────────┐  ┌─────────────┐  ┌────────┴─────────┐       │    │
│   │  │ GateClient  │  │ EventStream │  │ JdbcBackendClient│       │    │
│   │  │ (gRPC)      │  │   Client    │  │ RestBackendClient│       │    │
│   │  └─────────────┘  └─────────────┘  └──────────────────┘       │    │
│   │                                                               │    │
│   │  HTTP Server (Javalin) • Swagger UI • Admin Dashboard         │    │
│   └───────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────────┐
                    │       Exile         │
                    │   gRPC Services     │
                    └─────────────────────┘
```

## Features

- **Javalin HTTP Server** — REST API with OpenAPI/Swagger documentation
- **gRPC Gate Integration** — Secure mTLS connection to Exile services
- **Bidirectional Streaming** — Real-time job queue and event stream with ACK support
- **Dynamic Configuration** — Backend credentials delivered from Gate at runtime
- **Connection Pooling** — HikariCP for JDBC backends
- **Multi-Tenant Support** — Single tenant (one app per customer) or multi-tenant modes
- **Admin Dashboard** — Built-in status monitoring and log viewing
- **Hot Reload** — Configuration can be updated without restart

---

## Quick Start

### 1. Add Dependency

In your consuming application's `build.gradle`:

```groovy
dependencies {
    implementation project(':sati')
    
    // Or if published to a repository:
    // implementation 'com.tcn:sati-sdk:1.0.0'
}
```

### 2. Create Entry Point

```java
public class Main {
    public static void main(String[] args) {
        // Load configuration (e.g., from Base64-encoded config file)
        SatiConfig config = loadConfig();
        
        // Create your custom backend client
        CustomBackendClient backend = new CustomBackendClient(config);
        
        // Start the application
        SatiApp app = SatiApp.builder()
                .config(config)
                .backendClient(backend)
                .appName("My Tenant API")
                .start(8080);
        
        // Wire up dynamic config from Gate
        app.getTenantContext().getGateClient().setConfigListener(backend::onBackendConfigReceived);
        app.getTenantContext().getGateClient().startConfigPolling();
    }
}
```

### 3. Implement Backend Client

For **JDBC** backends, extend `JdbcBackendClient`:

```java
public class CustomBackendClient extends JdbcBackendClient {
    
    public CustomBackendClient(SatiConfig config) {
        super(config);
    }
    
    @Override
    protected void configureDataSource(HikariConfig hc, BackendConfig backendConfig) {
        // Configure your JDBC driver
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setJdbcUrl(buildJdbcUrl(backendConfig));
    }
    
    @Override
    protected String buildJdbcUrl(BackendConfig cfg) {
        return String.format("jdbc:mysql://%s:%s/%s",
                cfg.databaseHost, cfg.databasePort, cfg.databaseName);
    }
    
    // Implement SQL for your stored procedures
    @Override
    protected String getListPoolsSql() {
        return "SELECT pool_id AS PoolID, name AS PoolName, status AS Status FROM pools";
    }
    
    @Override
    protected String getPoolStatusSql() {
        return "SELECT pool_id AS PoolID, total AS TotalRecords, available AS AvailableRecords, status AS Status FROM pools WHERE pool_id = ?";
    }
    
    // ... implement remaining abstract methods
}
```

For **REST** backends, use `RestBackendClient` directly or extend it:

```java
SatiApp.builder()
    .config(config)
    .backendType(BackendType.REST)  // Use built-in REST client
    .appName("REST Tenant API")
    .start(8080);
```

---

## Configuration

### SatiConfig

The `SatiConfig` record holds all configuration for Gate connection and backend:

```java
SatiConfig config = SatiConfig.builder()
    // Gate/gRPC Connection (Required for Exile integration)
    .apiHostname("gate.example.com")
    .apiPort(443)
    .rootCert(caCertPem)
    .publicCert(clientCertPem)
    .privateKey(clientKeyPem)
    .org("my-organization")
    .tenant("my-tenant-id")
    
    // Static Backend (Optional - usually comes from Gate dynamically)
    .backendUrl("jdbc:mysql://localhost:3306/mydb")
    .backendUser("user")
    .backendPassword("password")
    .build();
```

### Gate Configuration File

Applications typically load config from a Base64-encoded JSON file:

```json
{
  "ca_certificate": "-----BEGIN CERTIFICATE-----\n...",
  "certificate": "-----BEGIN CERTIFICATE-----\n...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...",
  "api_endpoint": "https://gate.example.com:443"
}
```

Extract the organization from the certificate:

```java
String org = SatiConfig.extractOrgFromCert(certificate);
```

---

## Deployment Modes

### Single-Tenant Mode (Default)

One application instance per customer. Backend credentials come from Gate:

```java
SatiApp.builder()
    .config(config)
    .backendClient(customBackendClient)
    .appName("My Tenant API")
    .start(8080);
```

### Multi-Tenant Mode

Shared service handling multiple customers with automatic tenant discovery:

```java
SatiApp.builder()
    .backendType(BackendType.REST)
    .multiTenant(true)
    .tenantDiscovery(() -> fetchTenantsFromExternalService())
    .discoveryIntervalSeconds(30)
    .appName("Multi-Tenant API")
    .start(8080);
```

---

## Project Structure

```
sati/
├── src/main/java/com/tcn/sati/
│   ├── SatiApp.java                    # Main entry point with builder
│   ├── config/
│   │   ├── SatiConfig.java             # Configuration record
│   │   └── BackendType.java            # JDBC or REST enum
│   ├── core/
│   │   ├── job/
│   │   │   └── JobProcessor.java       # Processes jobs from Gate
│   │   ├── route/
│   │   │   ├── AdminRoutes.java        # /api/admin/* endpoints
│   │   │   ├── BackendRoutes.java      # /api/pools/* endpoints
│   │   │   ├── GateRoutes.java         # /api/gate/* endpoints
│   │   │   ├── TransferRoutes.java     # /api/transfer/* endpoints
│   │   │   └── AgentRoutes.java        # /api/agent/* endpoints
│   │   ├── service/
│   │   │   ├── TransferService.java    # Extensible transfer logic
│   │   │   └── AgentService.java       # Extensible agent logic
│   │   └── tenant/
│   │       ├── TenantContext.java      # Per-tenant resources
│   │       └── TenantManager.java      # Multi-tenant management
│   └── infra/
│       ├── backend/
│       │   ├── TenantBackendClient.java    # Interface for backends
│       │   ├── jdbc/
│       │   │   └── JdbcBackendClient.java  # Abstract JDBC implementation
│       │   └── rest/
│       │       └── RestBackendClient.java  # REST API implementation
│       ├── gate/
│       │   ├── GateClient.java         # gRPC connection to Gate
│       │   ├── EventStreamClient.java  # Bidirectional event stream
│       │   └── JobQueueClient.java     # Bidirectional job queue
│       └── logging/
│           └── MemoryLogAppender.java  # In-memory logs for dashboard
└── src/main/resources/
    └── public/                         # Admin dashboard static files
```

---

## Core Components

### SatiApp Builder

The fluent builder configures and starts the application:

| Method | Description |
|--------|-------------|
| `.config(SatiConfig)` | Gate connection and optional static backend config |
| `.backendType(BackendType)` | `JDBC` or `REST` (when not using custom client) |
| `.backendClient(TenantBackendClient)` | Custom backend implementation |
| `.transferService(TransferService)` | Override default transfer logic |
| `.agentService(AgentService)` | Override default agent logic |
| `.appName(String)` | Application name for Swagger UI |
| `.multiTenant(boolean)` | Enable multi-tenant mode |
| `.tenantDiscovery(Supplier)` | Auto-discover tenants |
| `.discoveryIntervalSeconds(long)` | Discovery poll interval |
| `.start(int port)` | Build and start on specified port |

### TenantBackendClient Interface

Implement this interface to connect to your tenant's data system:

```java
public interface TenantBackendClient extends AutoCloseable {
    // Pool operations
    List<PoolInfo> listPools();
    PoolStatus getPoolStatus(String poolId);
    List<PoolRecord> getPoolRecords(String poolId, int page);
    
    // Event handlers (called when Gate sends events)
    void handleTelephonyResult(TelephonyResult result);
    void handleTask(ExileTask task);
    void handleAgentCall(AgentCall call);
    void handleAgentResponse(AgentResponse response);
    void handleTransferInstance(TransferInstance transfer);
    void handleCallRecording(CallRecording recording);
    
    // Health check
    boolean isConnected();
}
```

### JdbcBackendClient (Abstract)

Provides common JDBC infrastructure:

- **HikariCP connection pooling** with configurable pool sizes
- **Async initialization** — doesn't block startup
- **Dynamic reconfiguration** — handles credential changes from Gate
- **Health monitoring** — tracks connection failures

Override these abstract methods:

| Method | Purpose |
|--------|---------|
| `configureDataSource()` | Set JDBC driver and connection properties |
| `buildJdbcUrl()` | Construct JDBC URL from components |
| `getListPoolsSql()` | SQL to list pools |
| `getPoolStatusSql()` | SQL to get pool status (with `?` placeholder) |
| `getPoolRecordsSql()` | SQL for paginated record retrieval |
| `getTelephonyResultSql()` | SQL/procedure for telephony results |
| `getTaskSql()` | SQL/procedure for tasks |
| `getAgentCallSql()` | SQL/procedure for agent calls |
| `getAgentResponseSql()` | SQL/procedure for agent responses |
| `getTransferInstanceSql()` | SQL/procedure for transfers |
| `getCallRecordingSql()` | SQL/procedure for recordings |

### GateClient

Manages gRPC connection to Exile/Gate:

- **mTLS authentication** using certificates from config
- **Lazy channel creation** with double-checked locking
- **Auto-reconnect** on connection failures
- **Config polling** — periodically fetches backend credentials from Gate

```java
// Get the Gate client from tenant context
GateClient gate = app.getTenantContext().getGateClient();

// Set listener for dynamic config
gate.setConfigListener(backendConfig -> {
    // Handle new database credentials
    myBackend.onBackendConfigReceived(backendConfig);
});

// Start polling (every 10 seconds)
gate.startConfigPolling();
```

### BackendConfig (from Gate)

Gate delivers database configuration dynamically:

| Field | Description |
|-------|-------------|
| `database_url` | Complete JDBC URL (optional) |
| `database_host` | Database hostname |
| `database_port` | Database port |
| `database_name` | Database/schema name |
| `database_type` | e.g., "IRIS", "Cache", "MySQL" |
| `database_username` | DB username |
| `database_password` | DB password |
| `use_tls` | Enable TLS for DB connection |
| `max_number_connections` | Connection pool size |

---

## HTTP Endpoints

The SDK automatically registers these routes:

### Admin Routes

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Redirect to admin dashboard |
| `/swagger` | GET | Swagger UI |
| `/swagger-docs` | GET | OpenAPI JSON |
| `/api/admin/status` | GET | System status (connections, jobs, events) |
| `/api/admin/logs` | GET | Recent log messages |
| `/api/admin/config` | POST | Load new configuration |

### Backend Routes

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/pools` | GET | List all pools |
| `/api/pools/{id}/status` | GET | Get pool status |
| `/api/pools/{id}/records` | GET | Get pool records (paginated) |

### Gate Routes

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/gate/status` | GET | Gate connection status |

### Multi-Tenant Routes (when enabled)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/tenants` | GET | List all tenant statuses |
| `/api/tenants/{key}/status` | GET | Specific tenant status |
| `/api/tenants/{key}/pools` | GET | Tenant's pools |

---

## Extending Services

Override default services to add custom behavior:

```java
public class MyTransferService extends TransferService {
    @Override
    public TransferResponse executeTransfer(TransferRequest req) {
        // Custom pre-transfer logic
        log.info("Custom transfer for agent: {}", req.getAgentId());
        
        // Call base implementation or custom logic
        return new TransferResponse(true, "Custom transfer completed");
    }
}

// Inject into builder
SatiApp.builder()
    .config(config)
    .backendClient(backend)
    .transferService(new MyTransferService())
    .start(8080);
```

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

*Exile API versions are pinned in `gradle.properties`.

---

## Example: Custom JDBC Backend

Complete example of a custom JDBC backend implementation:

```java
public class PostgresBackendClient extends JdbcBackendClient {
    
    public PostgresBackendClient(SatiConfig config) {
        super(config);
    }
    
    @Override
    protected void configureDataSource(HikariConfig hc, BackendConfig cfg) {
        hc.setDriverClassName("org.postgresql.Driver");
        hc.setJdbcUrl(buildJdbcUrl(cfg));
        
        // PostgreSQL-specific settings
        hc.addDataSourceProperty("reWriteBatchedInserts", "true");
    }
    
    @Override
    protected String buildJdbcUrl(BackendConfig cfg) {
        String base = String.format("jdbc:postgresql://%s:%s/%s",
                cfg.databaseHost, cfg.databasePort, cfg.databaseName);
        
        if (Boolean.TRUE.equals(cfg.useTls)) {
            return base + "?ssl=true&sslmode=require";
        }
        return base;
    }
    
    @Override
    protected String getListPoolsSql() {
        return "SELECT id AS \"PoolID\", name AS \"PoolName\", status AS \"Status\" FROM pools";
    }
    
    @Override
    protected String getPoolStatusSql() {
        return "SELECT id AS \"PoolID\", total_records AS \"TotalRecords\", " +
               "available_records AS \"AvailableRecords\", status AS \"Status\" " +
               "FROM pools WHERE id = ?";
    }
    
    @Override
    protected String getPoolRecordsSql() {
        return "SELECT id AS \"RecordID\", pool_id AS \"PoolID\", " +
               "first_name AS \"FirstName\", last_name AS \"LastName\", phone AS \"Phone\" " +
               "FROM records WHERE pool_id = ? ORDER BY id LIMIT ? OFFSET ?";
    }
    
    @Override
    protected String getTelephonyResultSql() {
        return "SELECT process_telephony_result(?::jsonb)";
    }
    
    @Override protected String getTaskSql() { return "SELECT process_task(?::jsonb)"; }
    @Override protected String getAgentCallSql() { return "SELECT process_agent_call(?::jsonb)"; }
    @Override protected String getAgentResponseSql() { return "SELECT process_agent_response(?::jsonb)"; }
    @Override protected String getTransferInstanceSql() { return "SELECT process_transfer(?::jsonb)"; }
    @Override protected String getCallRecordingSql() { return "SELECT process_recording(?::jsonb)"; }
}
```

---

## Logging

Sati uses SLF4J. Consuming applications must provide an implementation:

```groovy
// In build.gradle
implementation 'ch.qos.logback:logback-classic:1.5.6'
```

The `MemoryLogAppender` captures recent logs for the admin dashboard.

---

## Health Checks

Use `TenantContext.getStatus()` for comprehensive health information:

```java
TenantStatus status = app.getTenantContext().getStatus();
// Returns: tenantKey, running, gateConnected, backendConnected,
//          jobQueueConnected, eventStreamRunning, processedJobs,
//          failedJobs, processedEvents, createdAt
```

---
