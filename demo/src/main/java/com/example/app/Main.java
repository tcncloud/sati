package com.example.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.SatiApp;
import com.tcn.sati.config.SatiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

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
    }

    public static void main(String[] args) {
        log.info("Starting Demo Application...");

        // Ensure a config file exists (touching dummy path for the demo context)
        String configPath = System.getProperty("user.dir") + "/config.cfg";

        try {
            if (!Files.exists(Path.of(configPath))) {
                log.warn("Config file not found at " + configPath
                        + ". Sati relies on Gate configuration to run properly.");
                log.info("You can provide a valid Gate configuration string mapped to the above file.");
                // For demonstration purposes, we will exit if config is missing, since Sati
                // needs Gate to issue Database credentials
                System.exit(1);
            }

            SatiConfig satiConfig = loadGateConfig(configPath);
            AppBackendClient backend = new AppBackendClient(satiConfig);

            // Version and name
            String version = "1.0.0-Demo";
            String appName = "Sati Demo App";

            SatiApp satiApp = SatiApp.builder()
                    .config(satiConfig)
                    .backendClient(backend)
                    .agentService(CustomAgentService::new) // Example of how to override a service (in this case
                                                           // AgentService) with custom logic
                    .appName(appName)
                    .appVersion(version)
                    .start(8080);

            // Wire Gate config listener to the backend so DB can rotate credentials
            // properly
            if (satiApp.getTenantContext().getGateClient() != null) {
                satiApp.getTenantContext().getGateClient().setConfigListener(backend::onBackendConfigReceived);
                satiApp.getTenantContext().getGateClient().startConfigPolling();
            }

            // Watch the application config file
            ConfigWatcher watcher = new ConfigWatcher(configPath, newConfig -> {
                satiApp.getTenantContext().reconnectGate(newConfig);
            });
            watcher.start();

        } catch (Exception e) {
            log.error("Failed to start application", e);
            System.exit(1);
        }
    }

    public static SatiConfig loadGateConfig(String configPath) {
        try {
            log.info("Loading Gate config from: {}", configPath);
            String encoded = Files.readString(Path.of(configPath)).trim().replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(encoded);

            ObjectMapper mapper = new ObjectMapper();
            GateConfig gateConfig = mapper.readValue(decoded, GateConfig.class);

            URI uri = URI.create(gateConfig.apiEndpoint);
            String hostname = uri.getHost();
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            String orgFromCert = SatiConfig.extractOrgFromCert(gateConfig.certificate);

            return SatiConfig.builder()
                    .apiHostname(hostname)
                    .apiPort(port)
                    .rootCert(gateConfig.caCertificate)
                    .publicCert(gateConfig.certificate)
                    .privateKey(gateConfig.privateKey)
                    .org(orgFromCert)
                    .tenant(orgFromCert)
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to load Gate configuration", e);
        }
    }
}
