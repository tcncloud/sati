package com.tcn.exile.demo;

import com.tcn.exile.config.ExileClientManager;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo application showing how to use the sati client library.
 *
 * <p>This is a minimal, plain-Java application that:
 *
 * <ul>
 *   <li>Watches a config file for gate credentials
 *   <li>Connects to the gate server via the v3 WorkStream protocol
 *   <li>Handles jobs with stub responses (DemoJobHandler)
 *   <li>Logs all received events (DemoEventHandler)
 *   <li>Exposes /health and /status HTTP endpoints
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 *   # Place a config file (Base64-encoded JSON with certs):
 *   echo "$BASE64_CONFIG" > workdir/config/com.tcn.exiles.sati.config.cfg
 *
 *   # Run the demo:
 *   ./gradlew :demo:run
 *
 *   # Or with shadow jar:
 *   java -jar demo/build/libs/demo-all.jar
 *
 *   # Check status:
 *   curl http://localhost:8080/health
 *   curl http://localhost:8080/status
 * </pre>
 */
public class Main {

  static final String VERSION = "3.0.0-demo";
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    String configDir = System.getenv().getOrDefault("CONFIG_DIR", "");
    int maxConcurrency = Integer.parseInt(System.getenv().getOrDefault("MAX_CONCURRENCY", "20"));
    int maxDepth = Integer.parseInt(System.getenv().getOrDefault("MAX_DEPTH", "10"));
    int delayMs = Integer.parseInt(System.getenv().getOrDefault("PROCESSING_DELAY_MS", "50"));

    log.info("Starting sati-demo v{}", VERSION);
    log.info(
        "Pipeline config: maxConcurrency={}, maxDepth={}, processingDelayMs={}",
        maxConcurrency,
        maxDepth,
        delayMs);

    var plugin = new DemoPlugin(maxDepth, delayMs);

    // Build the client manager. It watches for config file changes,
    // creates/destroys the ExileClient automatically, and rotates
    // certificates before they expire.
    //
    // The capacityProvider tells sati how many more events the plugin can accept.
    // This drives credit-based flow control: sati only requests items from the
    // gate server when the plugin has room, preventing unbounded virtual thread pileup.
    var builder =
        ExileClientManager.builder()
            .clientName("sati-demo")
            .clientVersion(VERSION)
            .maxConcurrency(maxConcurrency)
            .capacityProvider(plugin::availableCapacity)
            .plugin(plugin);

    if (!configDir.isEmpty()) {
      builder.watchDirs(List.of(Path.of(configDir)));
    }

    var manager = builder.build();

    // Start the status HTTP server.
    var statusServer = new StatusServer(manager, plugin, port);
    statusServer.start();

    // Start watching for config and managing the client.
    manager.start();

    // Register shutdown hook for graceful cleanup.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Shutting down...");
                  manager.stop();
                  statusServer.close();
                },
                "shutdown"));

    log.info("sati-demo running on port {} — waiting for config file", port);
    log.info("Place config at: workdir/config/com.tcn.exiles.sati.config.cfg");

    // Keep main thread alive.
    Thread.currentThread().join();
  }
}
