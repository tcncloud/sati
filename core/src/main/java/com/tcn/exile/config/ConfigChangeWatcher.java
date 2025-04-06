/* 
 *  Copyright 2017-2024 original authors
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *  https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.tcn.exile.config;

import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.UnconfiguredException;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.DirectoryChangeEvent;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

@Singleton
@Requires(property = "sati.tenant.type", value = "single")
public class ConfigChangeWatcher implements ApplicationEventListener<StartupEvent> {
  @Inject
  ApplicationEventPublisher<ConfigEvent> eventPublisher;
  @Inject
  ObjectMapper objectMapper;

  private static final Logger log = LoggerFactory.getLogger(ConfigChangeWatcher.class);

  private static final String CONFIG_FILE_NAME = "com.tcn.exiles.sati.config.cfg";
  private DirectoryWatcher watcher;

  private ArrayList<Path> watchList;

  private String org = null;

  public ConfigChangeWatcher() throws IOException {
    log.info("creating watcher");
    watchList = new ArrayList<Path>();
    for (var dir : List.of("workdir/config", "/workdir/config")) {
      var path = Path.of(dir);
      if (path.toFile().exists()) {
        watchList.add(path);
      }
    }
    if (watchList.isEmpty()) {
      var f = new File("/workdir/config");
      if (!f.mkdirs()) {
        f = new File("./workdir/config");
        if (!f.mkdirs()) {
          throw new IOException(
              "No valid config directory found, and we don't have permissions to create one! Please create one in ./workdir/config or /workdir/config");
        }
      }
    }
    this.watcher = DirectoryWatcher.builder()
        .paths(watchList)
        .listener(event -> {
          log.debug("Event: {}", event);
          if (!event.path().getFileName().toString().equals(CONFIG_FILE_NAME)) {
            return;
          }
          Config changedConfig = null;

          switch (event.eventType()) {
            case CREATE:
            case MODIFY:
              changedConfig = loadConfig();
              if (changedConfig != null) {
                  log.info("Watcher detected config change, loaded config for org: {}", changedConfig.getOrg());
                  // Publish an event with the loaded config
                  ConfigEventInterface.EventType eventType = (event.eventType() == DirectoryChangeEvent.EventType.CREATE) ? 
                                                             ConfigEventInterface.EventType.CREATE : 
                                                             ConfigEventInterface.EventType.UPDATE;
                  eventPublisher.publishEvent(new ConfigEvent(this, changedConfig, eventType));
              } else {
                  log.warn("Watcher detected config change, but loading failed.");
                  // Optionally publish an unconfigured event
                  // eventPublisher.publishEvent(new ConfigEvent(this)); 
              }
              break;
            case DELETE:
              // Keep existing logic: emit ConfigEvent with unconfigured settings
              log.info("File {} event: {}", event.path(), event.eventType());
              // Use the public constructor which creates a default unconfigured internal
              // Config
              // if (this.org != null) {
              //   log.info("Unconfiguring org {}", this.org);
              //   eventPublisher.publishEvent(ConfigEvent.builder().withConfig(Config.builder()));
              // }
              var unconfiguredEvent = new ConfigEvent(this);
              // Optionally set the event type specifically to DELETE if needed by listeners
              // unconfiguredEvent.setEventType(ConfigEventInterface.EventType.DELETE); //
              // Need setter or Builder update if immutability is strict
              eventPublisher.publishEvent(unconfiguredEvent);
              break;
            default:
              log.info("Unexpected event: {}", event.eventType());
              break;
          }
        })
        .build();

    // loadConfig();
    // this.watcher.watch();
  }

  private Config loadConfig() {
    var configFile = findConfigFile();
    if (configFile != null) {
      log.info("Config file found: {}", configFile);
      // read the config file
      Config config = readConfig(configFile);
      if (config == null) {
        log.error("Error reading config file: {}", configFile);
        return null; // Return null if reading failed
      }

      // Use Config object directly for expiration check
      var expirationDate = config.getExpirationDate();
      if (expirationDate == null) {
        log.error("Could not determine expiration date from config: {}", configFile);
        // Decide how to handle this - maybe exit, maybe allow if it's just missing?
        // For now, let's exit to be safe, as before.
        System.exit(-1); // Or return null based on desired behavior
        return null;
      }

      if (expirationDate.before(new java.util.Date())) {
        log.error("Config file expired at {} {}", expirationDate, configFile);
        System.exit(-1); // Or return null based on desired behavior
        return null;
      }
      // Removed event publishing
      return config; // Return the valid Config object
    } else {
      log.info("Config file not found");
      return null; // Return null if no file found
    }
  }

  public Path findConfigFile() {
    for (var path : watchList) {
      var configFile = path.resolve(CONFIG_FILE_NAME);
      if (configFile.toFile().exists()) {
        return configFile;
      }
    }
    return null;
  }

  private Path findSuitableConfigDir() {
    for (var path : watchList) {
      if (path.toFile().canWrite())
        return path;
    }
    return null;
  }

  public synchronized void writeConfig(String payload) throws UnconfiguredException {
    if (findConfigFile() == null) {
      Path writeDir = findSuitableConfigDir();
      if (writeDir == null) {
        throw new UnconfiguredException(
            String.format("Can't find a suitable location for the config file, looked in {}", watchList.toString()));
      }

      var cfgFile = Path.of(writeDir.toFile().getAbsolutePath(), CONFIG_FILE_NAME);
      FileWriter fw = null;
      try {
        fw = new FileWriter(cfgFile.toFile());
        fw.write(payload);
        fw.close();
      } catch (IOException e) {
        throw new UnconfiguredException(e);
      }
    } else {
      try {
        FileWriter fw = new FileWriter(findConfigFile().toFile());
        fw.write(payload);
        fw.close();
      } catch (IOException e) {
        throw new UnconfiguredException(e);
      }
    }
  }

  private Config readConfig(Path configFile) {
    if (!configFile.toFile().canRead()) {
      log.error("Cannot read config file: {}", configFile);
      throw new RuntimeException("Cannot read config file: " + configFile);
    }
    try (var reader = new FileReader(configFile.toFile())) {
      var buf = new char[(int) configFile.toFile().length()];
      reader.read(buf);
      reader.close();
      return parseConfig(buf);

    } catch (IOException e) {
      log.error("Error reading config file: {}", configFile);
      throw new RuntimeException("Error reading config file: " + configFile, e);
    }
  }

  private Config parseConfig(char[] payload) throws IOException {
    var buf = Base64.getDecoder().decode(new String(payload).trim());
    var map = (HashMap<String, String>) objectMapper.readValue(buf, HashMap.class);

    // Create and populate a Config object directly
    Config config = new Config();
    config.setRootCert(map.get("ca_certificate"));
    config.setPublicCert(map.get("certificate"));
    config.setPrivateKey(map.get("private_key"));
    config.setFingerprintSha256(map.get("fingerprint_sha256"));
    config.setFingerprintSha256String(map.get("fingerprint_sha256_string"));
    config.setApiEndpoint(map.get("api_endpoint"));
    config.setCertificateName(map.get("certificate_name"));
    config.setCertificateDescription(map.get("certificate_description"));
    config.setUnconfigured(false); // Mark as configured since we parsed it

    return config; // Return the Config object
  }

  @Override
  public void onApplicationEvent(StartupEvent event) {
    Config loadedConfig = loadConfig(); // Capture the returned Config
    if (loadedConfig != null) {
        log.info("Successfully loaded initial config for org: {}", loadedConfig.getOrg());
        // Publish initial configuration event on startup
        eventPublisher.publishEvent(new ConfigEvent(this, loadedConfig, ConfigEventInterface.EventType.CREATE));
    } else {
        log.warn("Initial config load failed or file not found.");
        // Optionally publish an unconfigured event on startup if needed
        // eventPublisher.publishEvent(new ConfigEvent(this));
    }

    // when the application starts, start watching the config directory
    watcher.watchAsync();
  }
}