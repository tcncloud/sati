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
        String backendUrl, // JDBC URL or REST base URL
        String backendUser, // DB user or API client ID
        String backendPassword // DB password or API client secret
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
     * Extract the organization ID from an X.509 certificate's Distinguished Name.
     * The org is stored in the "O=" field of the certificate's subject.
     * 
     * @param publicCert PEM-encoded certificate
     * @return The organization ID, or null if not found
     */
    public static String extractOrgFromCert(String publicCert) {
        if (publicCert == null || publicCert.isBlank()) {
            return null;
        }
        try {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(publicCert.getBytes());
            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) cf.generateCertificate(bais);
            String dn = cert.getSubjectX500Principal().getName();

            // Extract O= (Organization) from Distinguished Name
            for (String part : dn.split(",")) {
                if (part.trim().startsWith("O=")) {
                    return part.substring(part.indexOf("=") + 1).trim();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
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

        public Builder apiHostname(String val) {
            this.apiHostname = val;
            return this;
        }

        public Builder apiPort(int val) {
            this.apiPort = val;
            return this;
        }

        public Builder rootCert(String val) {
            this.rootCert = val;
            return this;
        }

        public Builder publicCert(String val) {
            this.publicCert = val;
            return this;
        }

        public Builder privateKey(String val) {
            this.privateKey = val;
            return this;
        }

        public Builder org(String val) {
            this.org = val;
            return this;
        }

        public Builder tenant(String val) {
            this.tenant = val;
            return this;
        }

        public Builder backendUrl(String val) {
            this.backendUrl = val;
            return this;
        }

        public Builder backendUser(String val) {
            this.backendUser = val;
            return this;
        }

        public Builder backendPassword(String val) {
            this.backendPassword = val;
            return this;
        }

        public SatiConfig build() {
            return new SatiConfig(
                    apiHostname, apiPort, rootCert, publicCert, privateKey, org, tenant,
                    backendUrl, backendUser, backendPassword);
        }
    }
}
