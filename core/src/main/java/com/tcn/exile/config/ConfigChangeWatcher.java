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

import com.tcn.exile.gateclients.UnconfiguredException;
import io.methvin.watcher.DirectoryWatcher;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.serde.ObjectMapper;
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
public class ConfigChangeWatcher implements ApplicationEventListener<StartupEvent> {
  @Inject
  ApplicationEventPublisher<ConfigEvent> eventPublisher;
  @Inject
  ObjectMapper objectMapper;

  private static final Logger log = LoggerFactory.getLogger(ConfigChangeWatcher.class);

  private static final String CONFIG_FILE_NAME = "com.tcn.exiles.sati.config.cfg";
  private DirectoryWatcher watcher;

  private ArrayList<Path> watchList;

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
        if (!f.mkdirs()){
            f = new File("./workdir/config");
            if (!f.mkdirs()){
                throw new IOException("No valid config directory found, and we don't have permissions to create one! Please create one in ./workdir/config or /workdir/config");
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
          switch (event.eventType()) {
            case CREATE:
            case MODIFY:
              loadConfig();
              break;
            case DELETE:
              // emit ConfigEvent with unconfigured settings
              log.info("File {} event: {}", event.path(), event.eventType());
              var ev = ConfigEvent.builder().withUnconfigured(true).withSourceObject(this).build();
              eventPublisher.publishEvent(ev);
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

  private void loadConfig() {
    var configFile = findConfigFile();
    if (configFile != null) {
      log.info("Config file found: {}", configFile);
      // read the config file
      ConfigEvent evt = readConfig(configFile);
      // and publish an event
      eventPublisher.publishEvent(evt);
    } else {
      log.info("Config file not found");
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
        throw new UnconfiguredException(String.format("Can't find a suitable location for the config file, looked in {}", watchList.toString()));
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

  private ConfigEvent readConfig(Path configFile) {
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

  private ConfigEvent parseConfig(char[] payload) throws IOException {
    var buf = Base64.getDecoder().decode(new String(payload).trim());
    var map = (HashMap<String, String>) objectMapper.readValue(buf, HashMap.class);
//        log.debug("Config: {}", map);
    return ConfigEvent
        .builder()
        .withSourceObject(this)
        .withUnconfigured(false)
        .withRootCert(map.get("ca_certificate"))
        .withPublicCert(map.get("certificate"))
        .withPrivateKey(map.get("private_key"))
        .withFingerprintSha256(map.get("fingerprint_sha256"))
        .withFingerprintSha256String(map.get("fingerprint_sha256_string"))
        .withApiEndpoint(map.get("api_endpoint"))
        .withCertificateName(map.get("certificate_name"))
        .withCertificateDescription(map.get("certificate_description"))
        .build();
  }

  @Override
  public void onApplicationEvent(StartupEvent event) {
    loadConfig();
    // when the application starts, start watching the config directory
    watcher.watchAsync();
  }
}
