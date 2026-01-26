package com.tcn.sati.config;

/**
 * Defines the type of tenant backend connection.
 * 
 * JDBC - Direct database connection via JDBC (e.g., IRIS/Caché, SQL Server)
 * REST - HTTP REST API connection (e.g., Velosidy cloud API)
 */
public enum BackendType {
    JDBC,
    REST
}
