package com.example.app;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.jdbc.JdbcBackendClient;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.zaxxer.hikari.HikariConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppBackendClient extends JdbcBackendClient {
    private static final Logger log = LoggerFactory.getLogger(AppBackendClient.class);

    public AppBackendClient(SatiConfig config) {
        super(config);
    }

    @Override
    protected void configureDataSource(HikariConfig hikariConfig, BackendConfig backendConfig) {
        log.info("Configuring PostgreSQL HikariDataSource...");
        hikariConfig.setDriverClassName("org.postgresql.Driver");

        // Generic postgres properties config
        hikariConfig.addDataSourceProperty("stringtype", "unspecified");
    }

    @Override
    protected String buildJdbcUrl(BackendConfig backendConfig) {
        return String.format("jdbc:postgresql://%s:%d/%s",
                backendConfig.databaseHost,
                backendConfig.databasePort,
                backendConfig.databaseName);
    }

    // ========== SQL Implementation Stubs ==========
    // In a real application, you would return valid stored procedures or queries
    // here

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
        return "SELECT 1"; // Dummy, in PG usually CALL my_procedure(?)
    }

    @Override
    protected String getTaskSql() {
        return "SELECT 1";
    }

    @Override
    protected String getAgentCallSql() {
        return "SELECT 1";
    }

    @Override
    protected String getAgentResponseSql() {
        return "SELECT 1";
    }

    @Override
    protected String getTransferInstanceSql() {
        return "SELECT 1";
    }

    @Override
    protected String getCallRecordingSql() {
        return "SELECT 1";
    }

    @Override
    protected String getPopAccountSql() {
        return "SELECT 1";
    }

    @Override
    protected String getSearchRecordsSql() {
        return "SELECT '{}'::jsonb";
    }

    @Override
    protected String getReadFieldsSql() {
        return "SELECT '{}'::jsonb";
    }

    @Override
    protected String getWriteFieldsSql() {
        return "SELECT 1";
    }

    @Override
    protected String getCreatePaymentSql() {
        return "SELECT 1";
    }

    @Override
    protected String getExecuteLogicSql() {
        return "SELECT '{}'::jsonb";
    }
}
