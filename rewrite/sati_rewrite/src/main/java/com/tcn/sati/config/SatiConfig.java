package com.tcn.sati.config;

/**
 * Unified configuration for Sati.
 * Contains all settings needed for Gate connection and tenant backend.
 */
public record SatiConfig(
        // Gate/gRPC connection
        String apiHostname,
        int apiPort,
        String rootCert,
        String publicCert,
        String privateKey,
        String org,
        String tenant,

        // Backend connection (works for both JDBC and REST)
        String backendUrl,       // JDBC URL or REST base URL
        String backendUser,      // DB user or API client ID
        String backendPassword   // DB password or API client secret
) {
    /**
     * Check if Gate connection is configured.
     */
    public boolean isGateConfigured() {
        return apiHostname != null && !apiHostname.isBlank() &&
                rootCert != null && !rootCert.isBlank() &&
                publicCert != null && !publicCert.isBlank() &&
                privateKey != null && !privateKey.isBlank();
    }

    /**
     * Check if backend is configured.
     */
    public boolean isBackendConfigured() {
        return backendUrl != null && !backendUrl.isBlank();
    }

    /**
     * Legacy compatibility - returns isGateConfigured()
     */
    public boolean isConfigured() {
        return isGateConfigured();
    }

    /**
     * Builder for easier construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiHostname;
        private int apiPort;
        private String rootCert;
        private String publicCert;
        private String privateKey;
        private String org;
        private String tenant;
        private String backendUrl;
        private String backendUser;
        private String backendPassword;

        public Builder apiHostname(String val) { this.apiHostname = val; return this; }
        public Builder apiPort(int val) { this.apiPort = val; return this; }
        public Builder rootCert(String val) { this.rootCert = val; return this; }
        public Builder publicCert(String val) { this.publicCert = val; return this; }
        public Builder privateKey(String val) { this.privateKey = val; return this; }
        public Builder org(String val) { this.org = val; return this; }
        public Builder tenant(String val) { this.tenant = val; return this; }
        public Builder backendUrl(String val) { this.backendUrl = val; return this; }
        public Builder backendUser(String val) { this.backendUser = val; return this; }
        public Builder backendPassword(String val) { this.backendPassword = val; return this; }

        public SatiConfig build() {
            return new SatiConfig(
                apiHostname, apiPort, rootCert, publicCert, privateKey, org, tenant,
                backendUrl, backendUser, backendPassword
            );
        }
    }
}
