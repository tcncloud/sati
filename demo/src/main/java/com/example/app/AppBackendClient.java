package com.example.app;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.jdbc.JdbcBackendClient;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.zaxxer.hikari.HikariConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// PostgreSQL backend client — extends JdbcBackendClient with Postgres-specific
// config and SQL stubs. Gate sends DB credentials at runtime via
// onBackendConfigReceived() (inherited), which triggers HikariCP pool creation.
//
// Extension points to override:
//   configureDataSource() — set JDBC driver class and driver-specific properties
//   buildJdbcUrl()        — assemble the connection string from BackendConfig
//   get*Sql() methods     — point to your stored procedures
//   handle*() methods     — intercept incoming events from the event stream
//   popAccount(), searchRecords(), etc. — intercept incoming jobs
public class AppBackendClient extends JdbcBackendClient {
    private static final Logger log = LoggerFactory.getLogger(AppBackendClient.class);

    public AppBackendClient(SatiConfig config) {
        super(config);
    }

    // ================================================================
    // PostgreSQL Driver Configuration
    // ================================================================

    @Override
    protected void configureDataSource(HikariConfig hikariConfig, BackendConfig backendConfig) {
        log.info("Configuring PostgreSQL HikariDataSource...");
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.addDataSourceProperty("stringtype", "unspecified");
    }

    // Build the JDBC URL from the parts Gate sends us.
    // Called when BackendConfig.getEffectiveJdbcUrl() returns null
    // (i.e., Gate sent host/port/name separately instead of a full URL).
    @Override
    protected String buildJdbcUrl(BackendConfig backendConfig) {
        return String.format("jdbc:postgresql://%s:%s/%s",
                backendConfig.databaseHost,
                backendConfig.databasePort,
                backendConfig.databaseName);
    }

    // ================================================================
    // SQL Stubs — Point These to Your Stored Procedures
    //
    // In production, each method returns the SQL that calls your stored
    // procedure. The base JdbcBackendClient handles connection management,
    // parameter binding, and result parsing.
    //
    // Example (real deployment):
    // protected String getListPoolsSql() {
    // return "SELECT * FROM list_pools()";
    // }
    // ================================================================

    @Override
    protected String getListPoolsSql() {
        return "SELECT '' as \"PoolID\", '' as \"PoolName\", '' as \"Status\" LIMIT 0";
    }

    @Override
    protected String getPoolStatusSql() {
        return "SELECT ? as \"PoolID\", 0 as \"TotalRecords\", 0 as \"AvailableRecords\", '' as \"Status\" LIMIT 0";
    }

    @Override
    protected String getPoolRecordsSql() {
        return "SELECT '' as \"RecordID\", '' as \"PoolID\", '' as \"FirstName\", '' as \"LastName\", '' as \"Phone\" LIMIT 0";
    }

    @Override
    protected String getTelephonyResultSql() {
        return "SELECT 1"; // In production: CALL handle_telephony_result(?)
    }

    @Override
    protected String getTaskSql() {
        return "SELECT 1"; // In production: CALL handle_task(?)
    }

    @Override
    protected String getAgentCallSql() {
        return "SELECT 1"; // In production: CALL handle_agent_call(?)
    }

    @Override
    protected String getAgentResponseSql() {
        return "SELECT 1"; // In production: CALL handle_agent_response(?)
    }

    @Override
    protected String getTransferInstanceSql() {
        return "SELECT 1"; // In production: CALL handle_transfer_instance(?)
    }

    @Override
    protected String getCallRecordingSql() {
        return "SELECT 1"; // In production: CALL handle_call_recording(?)
    }

    @Override
    protected String getPopAccountSql() {
        return "SELECT 1"; // In production: CALL pop_account(?)
    }

    @Override
    protected String getSearchRecordsSql() {
        return "SELECT '{}' ::jsonb"; // In production: CALL search_records(?)
    }

    @Override
    protected String getReadFieldsSql() {
        return "SELECT '{}' ::jsonb"; // In production: CALL read_fields(?)
    }

    @Override
    protected String getWriteFieldsSql() {
        return "SELECT 1"; // In production: CALL write_fields(?)
    }

    @Override
    protected String getCreatePaymentSql() {
        return "SELECT 1"; // In production: CALL create_payment(?)
    }

    @Override
    protected String getExecuteLogicSql() {
        return "SELECT '{}' ::jsonb"; // In production: CALL execute_logic(?)
    }

    // ================================================================
    // Overriding Incoming Poll Events
    //
    // Events arrive from Exile via the EventStreamClient. Each event type
    // maps to a handle*() method. Override to add custom pre/post
    // processing — call super to run the SQL, or skip super to handle it
    // entirely yourself.
    //
    // Available: handleAgentCall, handleTelephonyResult, handleAgentResponse,
    // handleTask, handleTransferInstance, handleCallRecording
    // ================================================================

    // @Override
    // public void handleTelephonyResult(TelephonyResult result) {
    //     log.info("DEMO: Intercepted telephony result: callSid={}, status={}, result={}",
    //             result.callSid, result.status, result.result);

    //     // Example: skip the SQL call for certain statuses
    //     if ("CANCELLED".equals(result.status)) {
    //         log.info("DEMO: Skipping DB write for cancelled call {}", result.callSid);
    //         return; // don't call super — handle it ourselves
    //     }

    //     super.handleTelephonyResult(result);
    // }

    // ================================================================
    // Overriding Incoming Jobs
    //
    // Jobs arrive from Gate via the JobQueueClient. Each job type maps to
    // a method. Override to add custom behavior.
    //
    // Available: popAccount, searchRecords, readFields, writeFields,
    // createPayment, executeLogic
    // ================================================================

    // Custom request DTO — adds fields a client might send
    // public static class CustomPopAccountRequest extends PopAccountRequest {
    //     public String priorityFlag;
    //     public boolean isVip;
    // }

    // @Override
    // public PopAccountResult popAccount(PopAccountRequest request) {
    //     // If constructed via Jackson/JSON, and we configured Sati to use our subclass,
    //     // we can check and cast it (or just use it if the signature was overridden).
    //     // For demonstration, we just show how you can check:
    //     if (request instanceof CustomPopAccountRequest custom) {
    //         log.info("DEMO: Intercepted VIP popAccount: recordId={}, isVip={}, priority={}",
    //                 custom.recordId, custom.isVip, custom.priorityFlag);
    //     } else {
    //         log.info("DEMO: Intercepted popAccount: recordId={}, userId={}, callSid={}",
    //                 request.recordId, request.userId, request.callSid);
    //     }

    //     // Custom pre-processing here (look up extra data, enrich request, etc.)

    //     return super.popAccount(request); // delegate to base class for stored procedure
    // }
}
