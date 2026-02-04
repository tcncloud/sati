package com.tcn.sati.config;

/**
 * Defines the type of tenant backend connection.
 * 
 * JDBC - Direct database connection via JDBC
 * REST - HTTP REST API connection
 */
public enum BackendType {
    JDBC,
    REST
}
