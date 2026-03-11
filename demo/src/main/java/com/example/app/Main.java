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
import java.util.List;

// Entry point for a single-tenant Sati app.
//
// Shows: config discovery, config loading, PostgreSQL backend,
// service override, config file watcher, and logback integration.
//
// Quick start:
//   mkdir -p workdir/config
//   echo "BASE64_GATE_CONFIG" > workdir/config/com.tcn.exiles.sati.config.cfg
//   ./gradlew :demo:run
//   Dashboard: http://localhost:8080/
//   Swagger:   http://localhost:8080/swagger
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String CONFIG_FILE_NAME = "com.tcn.exiles.sati.config.cfg";

    // Docker absolute path first, then local dev relative path
    private static final List<Path> CONFIG_SEARCH_PATHS = List.of(
            Path.of("/workdir/config"),
            Path.of("workdir/config"));

    // JSON structure of the Gate config file (after Base64 decode)
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
        log.info("Starting Application...");

        // Config Discovery
        String configPath = findConfigFile();

        try {
            if (!Files.exists(Path.of(configPath))) {
                log.warn("Config file not found at " + configPath
                        + ". Sati relies on Gate configuration to run properly.");
                log.info("You can provide a valid Gate configuration string mapped to the above file.");
                System.exit(1);
            }

            // Config Loading
            SatiConfig satiConfig = loadGateConfig(configPath);

            // PostgreSQL Backend
            AppBackendClient backend = new AppBackendClient(satiConfig);

            // Version: system prop (dev mode) or JAR manifest (Docker)
            String version = System.getProperty("app.version",
                    Main.class.getPackage().getImplementationVersion() != null
                            ? Main.class.getPackage().getImplementationVersion() : "dev");
            String appName = "Sati Demo";

            // Build and Start Sati
            // Any of the 7 services can be overridden via the builder:
            // .agentService(CustomAgentService::new)
            // .transferService(...)
            // .scrubListService(...)
            // .skillsService(...)
            // .nclRulesetService(...)
            // .voiceRecordingService(...)
            // .journeyBufferService(...)
            SatiApp satiApp = SatiApp.builder()
                    .config(satiConfig)
                    .backendClient(backend)
                    // .agentService(CustomAgentService::new) // example of overriding agent service
                    .appName(appName)
                    .appVersion(version)
                    .start(8080);

            // Register custom routes (shows up in Swagger automatically)
            // CustomRoutes.register(satiApp.getApp());

            // Wire Gate Config Listener (DB credential rotation)
            // When Gate sends new backend credentials, the backend client
            // automatically recreates the HikariCP pool.
            if (satiApp.getTenantContext().getGateClient() != null) {
                satiApp.getTenantContext().getGateClient().setConfigListener(backend::onBackendConfigReceived);
                satiApp.getTenantContext().getGateClient().startConfigPolling();
            }

            // Config File Watcher (certificate rotation)
            // Monitors the Gate config file on disk. If it changes (e.g., new TLS certs),
            // the watcher reloads it and reconnects gRPC.
            ConfigWatcher watcher = new ConfigWatcher(configPath, newConfig -> {
                satiApp.getTenantContext().reconnectGate(newConfig);
            });
            watcher.start();

        } catch (Exception e) {
            log.error("Failed to start application", e);
            System.exit(1);
        }
    }

    // Load Gate config from a Base64-encoded file.
    // The file contains JSON with TLS certs and the Gate API endpoint.
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

    // Search known paths for the config file.
    // Checks /workdir/config (Docker) first, then workdir/config (local dev).
    static String findConfigFile() {
        for (Path dir : CONFIG_SEARCH_PATHS) {
            Path configFile = dir.resolve(CONFIG_FILE_NAME);
            if (configFile.toFile().exists()) {
                String path = configFile.toAbsolutePath().toString();
                System.out.println("Found config at: " + path);
                return path;
            }
        }

        throw new RuntimeException(
                "Config file not found. Searched:\n" +
                        "  - /workdir/config/" + CONFIG_FILE_NAME + "\n" +
                        "  - workdir/config/" + CONFIG_FILE_NAME + "\n" +
                        "Please place your config file in one of these locations.");
    }
}
