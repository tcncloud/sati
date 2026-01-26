package com.tcn.sati.infra.backend;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.jdbc.JdbcBackendClient;
import com.tcn.sati.infra.backend.rest.RestBackendClient;

/**
 * Factory for creating the appropriate TenantBackendClient based on configuration.
 */
public class TenantBackendFactory {

    /**
     * Create a TenantBackendClient based on the backend type.
     * 
     * @param config The Sati configuration
     * @param backendType JDBC for database connections, REST for API connections
     */
    public static TenantBackendClient create(SatiConfig config, BackendType backendType) {
        return switch (backendType) {
            case JDBC -> new JdbcBackendClient(config);
            case REST -> new RestBackendClient(config);
        };
    }
}
