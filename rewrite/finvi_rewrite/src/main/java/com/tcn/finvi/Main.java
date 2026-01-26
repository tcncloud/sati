package com.tcn.finvi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.SatiApp;
import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Finvi Application - Thin shell that delegates to Sati.
 * 
 * Application is configured and started with the SatiApp builder.
 * All business logic, routes, database connections, and gRPC handling
 * are provided by sati.
 * 
 * Configuration flow:
 * 1. Gate certificates loaded from com.tcn.exiles.sati.config.cfg file
 * 2. Database credentials received dynamically from Gate via gRPC
 */
public class Main {

    // DTO for the Gate config file (certificates only, no DB info)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GateConfig {
        @JsonProperty("ca_certificate")
        public String caCertificate;
        @JsonProperty("certificate")
        public String certificate;
        @JsonProperty("private_key")
        public String privateKey;
        @JsonProperty("api_endpoint")
        public String apiEndpoint;
        @JsonProperty("org")
        public String org;
    }

    public static void main(String[] args) {
        System.out.println("Starting FINVI...");

        // Load Gate config and start - database config will come from Gate dynamically!
        SatiApp.builder()
                .config(loadGateConfig())
                .backendType(BackendType.JDBC) // Finvi uses JDBC to IRIS/CACHE database
                .transferService(new FinviTransferService()) // Custom transfer logic
                .appName("Finvi API")
                .start(8080);
    }

    private static SatiConfig loadGateConfig() {
        try {
            // Check for ENV var, else default path
            String configPathStr = System.getenv("SATI_CONFIG_PATH");
            if (configPathStr == null) {
                configPathStr = "/Projects/ExileProject/finvi/workdir/config/com.tcn.exiles.sati.config.cfg";
            }

            File configFile = new File(configPathStr);
            if (!configFile.exists()) {
                throw new RuntimeException("Config file not found at " + configPathStr +
                        " - Gate connection required to receive database configuration");
            }

            System.out.println("Loading Gate config from: " + configPathStr);
            String encoded = Files.readString(configFile.toPath()).trim().replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(encoded);

            ObjectMapper mapper = new ObjectMapper();
            GateConfig gateConfig = mapper.readValue(decoded, GateConfig.class);

            // Parse API Endpoint
            URI uri = URI.create(gateConfig.apiEndpoint);
            String hostname = uri.getHost();
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equals(uri.getScheme()) ? 443 : 80;
            }

            // Note: No database config, it will come from Gate dynamically.
            return SatiConfig.builder()
                    .apiHostname(hostname)
                    .apiPort(port)
                    .rootCert(gateConfig.caCertificate)
                    .publicCert(gateConfig.certificate)
                    .privateKey(gateConfig.privateKey)
                    .org(gateConfig.org)
                    .tenant(System.getenv("AG_TENANT") != null ? System.getenv("AG_TENANT") : "default-tenant")
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load Gate configuration: " + e.getMessage(), e);
        }
    }
}
