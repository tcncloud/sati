/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tcn.exile.multi.config;

import com.tcn.exile.config.Config;
import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.gateclients.ConfigEventInterface;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.DirectoryChangeEvent;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.serde.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Watches specified directories for configuration files where each filename
 * represents a tenant ID. Publishes ConfigEvents based on file changes.
 */
@Singleton
@Requires(property = "sati.tenant.type", value = "multi")
public class MultiTenantConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(MultiTenantConfigWatcher.class);

    private final ApplicationEventPublisher<ConfigEvent> eventPublisher;
    private final ObjectMapper objectMapper; // For parsing file content
    private DirectoryWatcher watcher;
    private final List<Path> watchDirs = new ArrayList<>();

    // Constructor with injected dependencies
    @Inject
    public MultiTenantConfigWatcher(ApplicationEventPublisher<ConfigEvent> eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        
        log.info("!!!!! INITIALIZING MultiTenantConfigWatcher - THIS IS A DISTINCTIVE MESSAGE !!!!!");
        // Define directories to watch
        List<String> potentialDirs = List.of("./workdir/config", "/workdir/config");

        for (var dir : potentialDirs) {
            var path = Paths.get(dir);
            try {
                if (!Files.exists(path)) {
                    log.warn("Directory {} does not exist, attempting to create.", path.toAbsolutePath());
                    Files.createDirectories(path);
                }
                if (Files.isDirectory(path) && Files.isWritable(path)) {
                    watchDirs.add(path.toAbsolutePath()); // Store absolute path
                    log.info("Added directory to watch list: {}", path.toAbsolutePath());
                } else {
                    log.warn("Directory {} is not accessible or writable.", path.toAbsolutePath());
                }
            } catch (IOException e) {
                log.error("Failed to access or create directory {}: {}", path.toAbsolutePath(), e.getMessage());
            }
        }

        if (watchDirs.isEmpty()) {
            log.error("No valid directories found or created in {}. MultiTenantConfigWatcher will not be active.", potentialDirs);
        }
    }

    @PostConstruct
    void startWatching() {
        log.info("!!!!! MultiTenantConfigWatcher @PostConstruct called !!!!!");
        if (watchDirs.isEmpty()) {
            log.warn("No directories configured for watching. Skipping watcher start.");
            return;
        }
        try {
            log.info("Starting directory watcher for: {}", watchDirs);
            watcher = DirectoryWatcher.builder()
                .paths(watchDirs)
                .listener(this::handleFileEvent)
                .build();

            // Perform initial scan for existing files
            scanInitialFiles();

            watcher.watchAsync();
            log.info("Directory watcher started successfully.");

        } catch (IOException e) {
            log.error("Failed to start DirectoryWatcher", e);
        }
    }

    @PreDestroy
    void stopWatching() {
        log.info("Stopping directory watcher...");
        if (watcher != null) {
            try {
                watcher.close();
                log.info("Directory watcher stopped successfully.");
            } catch (IOException e) {
                log.error("Error stopping directory watcher", e);
            }
        }
    }

    private void scanInitialFiles() {
        log.info("Performing initial scan of watched directories...");
        for (Path dir : watchDirs) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                      .forEach(path -> processConfigFile(path, DirectoryChangeEvent.EventType.CREATE)); // Treat initial as CREATE
            } catch (IOException e) {
                log.error("Error scanning directory {}: {}", dir, e.getMessage());
            }
        }
        log.info("Initial scan complete.");
    }

    private void handleFileEvent(DirectoryChangeEvent event) {
        Path path = event.path();
        // Ignore temporary files or directories, unless it's a delete event for a potential config file
        if (!Files.isRegularFile(path) && event.eventType() != DirectoryChangeEvent.EventType.DELETE) {
            // It might be a directory event or temp file, ignore.
            log.trace("Ignoring event for non-regular file or directory: {}", path);
            return;
        }
        log.debug("Watcher event: Type={}, Path={}", event.eventType(), path);
        processConfigFile(path, event.eventType());
    }

    private void processConfigFile(Path configFile, DirectoryChangeEvent.EventType eventType) {
        String tenantId = configFile.getFileName().toString();
        log.info("Processing config file event for tenant '{}' [File: {}, Event: {}]", tenantId, configFile, eventType);

        if (eventType == DirectoryChangeEvent.EventType.DELETE) {
            publishDeleteEvent(tenantId);
            return;
        }

        // Handle CREATE and MODIFY
        try {
            // Ensure file still exists before reading (might be deleted quickly after MODIFY)
            if (!Files.exists(configFile)) {
                log.warn("Config file {} for tenant '{}' disappeared before processing.", configFile, tenantId);
                return;
            }
            String content = Files.readString(configFile);
            if (content == null || content.isBlank()) {
                log.warn("Config file for tenant '{}' is empty: {}. Skipping.", tenantId, configFile);
                return;
            }

            Config config = parseConfigContent(content);

            ConfigEventInterface.EventType configEventType = (eventType == DirectoryChangeEvent.EventType.CREATE) ?
                                                              ConfigEventInterface.EventType.CREATE :
                                                              ConfigEventInterface.EventType.UPDATE;
            publishChangeEvent(tenantId, configEventType, config);

        } catch (IOException e) {
            log.error("Error reading config file {} for tenant '{}': {}", configFile, tenantId, e.getMessage());
        } catch (Exception e) {
            log.error("Error parsing config file content for tenant '{}' [File: {}]: {}", tenantId, configFile, e.getMessage(), e);
        }
    }

    private Config parseConfigContent(String base64Payload) throws IOException {
        if (base64Payload == null || base64Payload.isBlank()) {
            throw new IOException("Cannot parse null or blank config payload.");
        }
        try {
            byte[] buf = Base64.getDecoder().decode(base64Payload.trim());
            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(buf, HashMap.class);

            return Config.builder()
                .rootCert(map.get("ca_certificate"))
                .publicCert(map.get("certificate"))
                .privateKey(map.get("private_key"))
                .fingerprintSha256(map.get("fingerprint_sha256"))
                .fingerprintSha256String(map.get("fingerprint_sha256_string"))
                .apiEndpoint(map.get("api_endpoint"))
                .certificateName(map.get("certificate_name"))
                .certificateDescription(map.get("certificate_description"))
                // Builder automatically sets unconfigured=false if key fields present
                .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 content", e);
        } catch (IOException e) {
            throw new IOException("Failed to parse JSON content", e);
        }
    }

    private void publishChangeEvent(String tenantIdFromFile, ConfigEventInterface.EventType type, Config config) {
        String effectiveTenantId = config.getOrg(); // Get tenant ID from cert CN

        if (effectiveTenantId == null) {
             log.warn("Certificate CN (organization) is missing in config parsed from file '{}'. Cannot reliably determine tenant ID. Publishing event based on filename.", tenantIdFromFile);
             // If org is missing from cert, we can't reliably use it. We could potentially
             // use the filename, but this might lead to inconsistencies if the cert is updated later.
             // For now, we'll log a warning and proceed, but this indicates a potential config issue.
             // effectiveTenantId = tenantIdFromFile; // Uncomment this line to use filename as fallback
             // For safety, let's NOT publish if the primary identifier (cert CN) is missing.
             log.error("Aborting event publish for file '{}' due to missing CN in certificate.", tenantIdFromFile);
             return;
        }

        if (!effectiveTenantId.equals(tenantIdFromFile)) {
            log.warn("Tenant ID derived from filename ('{}') does not match CN in certificate ('{}'). Using ID from certificate for event.",
                     tenantIdFromFile, effectiveTenantId);
            // We trust the certificate CN as the source of truth for the tenant ID.
        }

        log.info("Publishing {} event for effective tenant '{}' (from file {})", type, effectiveTenantId, tenantIdFromFile);
        ConfigEvent event = new ConfigEvent(this, config, type); // Use the parsed config
        eventPublisher.publishEvent(event);
    }

    private void publishDeleteEvent(String tenantIdFromFile) {
        log.info("File deleted for tenant '{}'. Publishing DELETE event.", tenantIdFromFile);
        // For DELETE, we publish a standard unconfigured event.
        // The TenantFactory listener will need to use the context/qualifier to know which tenant bean to destroy.
        ConfigEvent event = new ConfigEvent(this); // Creates default unconfigured config
        eventPublisher.publishEvent(event);
        log.warn("Published standard unconfigured event for DELETE of tenant file '{}'. Listener needs context to identify target.", tenantIdFromFile);
    }
} 