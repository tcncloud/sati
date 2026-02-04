# Sati Rewrite - Thin Shell Architecture

This repository contains the rewritten **Sati** library and tenant implementations using a **Thin Shell Architecture**.

## Goal

Simplify tenant integrations by moving **all** application logic (routes, services, gRPC clients, database/API connections) into the shared `sati_rewrite` library. Tenant apps (like `finvi_rewrite`) become extremely lightweight shells that only handle configuration loading.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         TENANT SHELL                                 │
│  (finvi_rewrite, velosidy_rewrite, etc.)                            │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Main.java (~50 lines)                                         │ │
│  │  - Load config from environment/files                          │ │
│  │  - SatiApp.builder().config().backendType().start()            │ │
│  └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
                                   │ uses
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         SATI_REWRITE                                 │
│                      (Core Library)                                  │
├─────────────────────────────────────────────────────────────────────┤
│  SatiApp.java              - Unified builder & entry point          │
│                                                                      │
│  config/                                                             │
│  ├── BackendType.java      - JDBC or REST                           │
│  └── SatiConfig.java       - All configuration options              │
│                                                                      │
│  core/                                                               │
│  ├── route/                - HTTP endpoints (Javalin + Swagger)     │
│  ├── service/              - Business logic (extensible)            │
│  ├── job/                  - Job processing from Gate               │
│  │   └── JobProcessor.java - Thread pool for job execution          │
│  └── tenant/               - Multi-tenant support                   │
│      ├── TenantContext.java   - All resources for one tenant        │
│      └── TenantManager.java   - Manages multiple tenants            │
│                                                                      │
│  infra/                                                              │
│  ├── gate/                                                           │
│  │   ├── GateClient.java      - gRPC connection to Exile            │
│  │   └── JobStreamClient.java - Streams jobs from Gate              │
│  └── backend/                                                        │
│      ├── TenantBackendClient.java   - Interface                     │
│      ├── jdbc/JdbcBackendClient.java - Abstract JDBC (extend this)  │
│      └── rest/RestBackendClient.java - HTTP API via HttpClient      │
└─────────────────────────────────────────────────────────────────────┘
```

## Deployment Modes

### Single-Tenant Mode (Finvi with JDBC)

One instance per customer. Requires custom backend client for JDBC:

```java
// Create tenant-specific backend client
FinviBackendClient backendClient = new FinviBackendClient();

SatiApp satiApp = SatiApp.builder()
    .config(loadConfig())
    .backendClient(backendClient)           // Inject custom client
    .transferService(new FinviTransferService())
    .appName("Finvi API")
    .start(8080);

// Wire up dynamic config from Gate
satiApp.getTenantContext().getGateClient()
    .addConfigListener(backendClient::onBackendConfigReceived);
```

### Multi-Tenant Mode (Velosidy)

Shared instance serving multiple customers. Tenants discovered from external service.

```java
SatiApp.builder()
    .backendType(BackendType.REST)
    .multiTenant(true)
    .tenantDiscovery(() -> velosidyClient.fetchTenants())
    .discoveryIntervalSeconds(30)
    .appName("Velosidy API")
    .start(8080);
```

## Project Structure

### `sati_rewrite/` - Core Library

| Path | Purpose |
|------|---------|
| `SatiApp.java` | Unified builder supporting single & multi-tenant modes |
| `config/BackendType.java` | Enum: `JDBC` or `REST` |
| `config/SatiConfig.java` | Configuration record with builder |
| `core/route/*.java` | HTTP endpoints with Swagger annotations |
| `core/service/*.java` | Business logic (can be overridden) |
| `core/job/JobProcessor.java` | Thread pool for processing Gate jobs |
| `core/tenant/TenantContext.java` | All resources for a single tenant |
| `core/tenant/TenantManager.java` | Manages multiple TenantContexts |
| `infra/gate/GateClient.java` | gRPC client for Exile/Gate |
| `infra/gate/JobStreamClient.java` | Persistent stream for receiving jobs |
| `infra/backend/jdbc/JdbcBackendClient.java` | Abstract base for JDBC backends (extend this) |
| `infra/backend/rest/RestBackendClient.java` | REST API connection |

### `finvi_rewrite/` - Finvi Tenant Shell

| Path | Purpose |
|------|---------|
| `Main.java` | Config loading + `SatiApp.builder()` call |
| `FinviBackendClient.java` | Extends `JdbcBackendClient` with IRIS/Caché driver + SQL |
| `FinviTransferService.java` | Custom transfer validation logic |
| `ConfigWatcher.java` | Hot-reload config file changes |

## Multi-Tenant Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                         SatiApp                                 │
│                                                                │
│  Mode: SINGLE_TENANT              Mode: MULTI_TENANT           │
│  ┌─────────────────┐              ┌─────────────────────────┐  │
│  │ TenantContext   │              │     TenantManager       │  │
│  │                 │              │                         │  │
│  │ • GateClient    │              │ Map<String, TenantCtx>  │  │
│  │ • JobStream     │              │                         │  │
│  │ • JobProcessor  │              │ • createTenant()        │  │
│  │ • BackendClient │              │ • destroyTenant()       │  │
│  │                 │              │ • getTenant()           │  │
│  └─────────────────┘              │ • discoverTenants()     │  │
│                                   └─────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### TenantContext

Each tenant has isolated resources:
- **GateClient** - gRPC connection to Exile
- **JobStreamClient** - Persistent stream for receiving jobs
- **JobProcessor** - Thread pool for executing jobs
- **TenantBackendClient** - Database or REST connection
- **ScheduledExecutorService** - Tenant-specific scheduled tasks

### TenantManager

Manages multiple tenants with:
- `ConcurrentHashMap<String, TenantContext>` - Thread-safe storage
- Automatic tenant discovery from external services
- Dynamic create/destroy on configuration changes
- Health monitoring

## Job Processing

Jobs are received from Gate via persistent gRPC stream and processed by a thread pool.

```
Gate (gRPC) ──────► JobStreamClient ──────► JobProcessor ──────► BackendClient
                          │                      │
                          │ auto-reconnect       │ thread pool
                          │ hung detection       │ (5 workers)
                          │                      │
                          └──────────────────────┴──► SubmitJobResults
```

### Supported Job Types

| Job Type | Description |
|----------|-------------|
| `listPools` | List available pools |
| `getPoolStatus` | Get status of a specific pool |
| `getPoolRecords` | Get records from a pool |
| `info` | Return server/version info |
| `diagnostics` | Return health diagnostics |
| `shutdown` | Graceful shutdown |

### Resilience Features

| Feature | Implementation |
|---------|----------------|
| **Auto-reconnect** | Exponential backoff (5s → 60s) |
| **Hung detection** | 45s timeout, force reconnect |
| **Graceful shutdown** | Drain queue, complete in-flight jobs |
| **Error isolation** | Per-job error handling, stream continues |

## API Endpoints

### Single-Tenant Mode

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/swagger` | GET | Swagger UI |
| `/api/status` | GET | Tenant status |
| `/api/backend/health` | GET | Backend connection health |
| `/api/gate/check` | GET | Gate gRPC connection check |
| `/api/pools` | GET | List all pools |
| `/api/pools/{id}/status` | GET | Get pool status |
| `/api/pools/{id}/records` | GET | Get pool records |
| `/api/transfer` | POST | Execute a transfer |
| `/api/agent/{id}/status` | GET | Get agent status |

### Multi-Tenant Mode

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/swagger` | GET | Swagger UI |
| `/api/tenants` | GET | List all tenants with status |
| `/api/tenants/{key}/status` | GET | Get specific tenant status |
| `/api/tenants/{key}/pools` | GET | List pools for tenant |
| `/api/tenants/{key}/pools/{id}/records` | GET | Get records for tenant |

## How to Run

### Finvi (Single-Tenant, JDBC Backend)

```bash
cd finvi_rewrite

# Run (uses config file from workdir/config)
./gradlew run
```

### Velosidy (Multi-Tenant, REST Backend)

```java
public class Main {
    public static void main(String[] args) {
        SatiApp.builder()
            .backendType(BackendType.REST)
            .multiTenant(true)
            .tenantDiscovery(() -> {
                // Fetch tenant configs from external service
                return velosidyClient.listTenants().stream()
                    .map(t -> new TenantManager.TenantConfig(
                        t.tenantKey(),
                        buildSatiConfig(t)
                    ))
                    .toList();
            })
            .discoveryIntervalSeconds(30)
            .appName("Velosidy API")
            .start(8080);
    }
}
```

## Configuration

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
| `transferService()` | default | Custom transfer service implementation |
| `agentService()` | default | Custom agent service implementation |
| `appName()` | auto | Application name for logs/Swagger |
| `multiTenant()` | `false` | Enable multi-tenant mode |
| `tenantDiscovery()` | none | Supplier for tenant discovery |
| `discoveryIntervalSeconds()` | `30` | Tenant discovery interval |

## Extending Services and DTOs

Tenants can extend both **service logic** and **request/response DTOs** with custom fields.

### Extending DTOs

Add tenant-specific fields by extending the base classes:

```java
// Extended request with Finvi-specific fields
public static class FinviTransferRequest extends TransferService.TransferRequest {
    private String accountNumber;
    private String priorityCode;
    
    public FinviTransferRequest(String agentId, String targetQueue, String accountNumber, String priorityCode) {
        super(agentId, targetQueue);
        this.accountNumber = accountNumber;
        this.priorityCode = priorityCode;
    }
    // getters/setters...
}

// Extended response with Finvi-specific fields
public static class FinviTransferResponse extends TransferService.TransferResponse {
    private String transferId;
    
    public FinviTransferResponse(boolean success, String message, String transferId) {
        super(success, message);
        this.transferId = transferId;
    }
    // getters/setters...
}
```

### Overriding Service Methods

Use the extended DTOs in your service override:

```java
public class FinviTransferService extends TransferService {
    @Override
    public TransferResponse executeTransfer(TransferRequest req) {
        // Extract extended fields if present
        if (req instanceof FinviTransferRequest finviReq) {
            log.debug("Account: {}, Priority: {}", 
                finviReq.getAccountNumber(), finviReq.getPriorityCode());
        }
        
        // Custom validation
        if (req.getTargetQueue().equals("restricted")) {
            return new TransferResponse(false, "Queue is restricted");
        }
        
        // Delegate to base, then enhance response
        TransferResponse base = super.executeTransfer(req);
        return new FinviTransferResponse(base.isSuccess(), base.getMessage(), "TXF-123");
    }
}
```

### Wiring It Up

```java
SatiApp.builder()
    .config(loadConfig())
    .backendClient(new FinviBackendClient())
    .transferService(new FinviTransferService())
    .start(8080);
```

## Swapping Database Technology

The architecture uses the **Template Method Pattern**, making it easy to swap database providers (e.g., moving from IRIS/Caché to PostgreSQL).

1.  **Update `build.gradle`**: Add the new JDBC driver dependency.
2.  **Extend `JdbcBackendClient`**: Implement the 3 required abstract methods.

```java
public class PostgresBackendClient extends JdbcBackendClient {
    @Override
    protected void configureDataSource(HikariConfig hc, BackendConfig cfg) {
        hc.setDriverClassName("org.postgresql.Driver");
    }

    @Override
    protected String buildJdbcUrl(BackendConfig cfg) {
        return String.format("jdbc:postgresql://%s:%d/%s", 
            cfg.host(), cfg.port(), cfg.db());
    }

    @Override
    protected String getListPoolsSql() {
        return "SELECT id, name FROM public.pools";
    }
}
```

3.  **Inject the new client**:

```java
SatiApp.builder()
    .backendClient(new PostgresBackendClient())
    .start(8080);
```

## Testing

```bash
# Check tenant status (single-tenant)
curl http://localhost:8080/api/status

# List tenants (multi-tenant)
curl http://localhost:8080/api/tenants

# List pools
curl http://localhost:8080/api/pools

# Test transfer
curl -X POST http://localhost:8080/api/transfer \
     -H "Content-Type: application/json" \
     -d '{"agentId":"123", "targetQueue":"Support"}'
```

## Key Design Decisions

1. **Native Java over frameworks** - No Micronaut, just `ConcurrentHashMap` and `ScheduledExecutorService`
2. **Single SatiApp entry point** - Builder pattern for all configuration
3. **TenantContext encapsulation** - All tenant resources in one object
4. **Generic backend abstraction** - `JDBC`/`REST` instead of vendor names
5. **Resilient job streaming** - Auto-reconnect, hung detection, graceful shutdown
6. **Single-tenant AND multi-tenant** - Same codebase supports both modes
7. **Extensible DTOs** - Hand-written classes (not records) allow tenants to extend request/response types

## Comparison: Old vs Rewrite

| Aspect | Old (Micronaut) | Rewrite (Native Java) |
|--------|-----------------|----------------------|
| **Framework** | Micronaut DI | Plain Java |
| **Multi-tenant** | Bean qualifiers, ApplicationContext | ConcurrentHashMap |
| **Job streaming** | ~400 lines across 5 classes | ~200 lines in 2 classes |
| **Tenant management** | ~500 lines | ~200 lines |
| **Config** | File watcher + beans | Simple config record |
| **Testing** | Needs DI container | Unit tests |
| **Startup** | Annotation processing | Instant |
| **Total LOC** | ~3000+ | ~1500 |
