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

package com.tcn.exile.demo.single;

import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.v2.GateClient;
import com.tcn.exile.gateclients.v2.GateClientConfiguration;
import com.tcn.exile.gateclients.v2.GateClientJobStream;
import com.tcn.exile.gateclients.v2.GateClientPollEvents;
import io.methvin.watcher.DirectoryWatcher;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Singleton
public class ConfigChangeWatcher implements ApplicationEventListener<StartupEvent> {
    private Config currentConfig = null;
    @Inject
    ApplicationContext context;

    @Inject
    Environment environment;

    @Inject
    ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeWatcher.class);

    private static final String CONFIG_FILE_NAME = "com.tcn.exiles.sati.config.cfg";
    private DirectoryWatcher watcher;

    private static final List<Path> watchList = List.of(Path.of("/workdir/config"), Path.of("workdir/config"));

    private String org = null;

    private void findValidConfigDir() {
        Optional<Path> validDir = watchList.stream()
                .filter(path -> path.toFile().exists())
                .findFirst();
        if (!validDir.isPresent()) {
            // try to create a local workdir/config directory
            var dir = Path.of("workdir/config");
            if (!dir.toFile().mkdirs()) {
                log.error("No valid config directory found, and we don't have permissions to create one! Please create one in ./workdir/config or /workdir/config");
            }
        } else {
            log.info("Found valid config directory: {}", validDir.get());
        }

    }

    private Optional<Config> getCurrentConfig() {
        log.debug("Current config: {}", currentConfig);
        Optional<Path> configDir = watchList.stream().filter(path -> path.toFile().exists())
                .findFirst();
        if (configDir.isPresent()) {
            if (configDir.get().resolve(Path.of(CONFIG_FILE_NAME)).toFile().exists()) {
                try {
                    var file = configDir.get().resolve(Path.of(CONFIG_FILE_NAME));
                    log.debug("Found valid config file: {}", file);
                    var data = Files.readAllBytes(file);
//                    var newConfig = Config.of(Arrays.copyOf(data, data.length-1), objectMapper);
                    var newConfig = Config.of(data, objectMapper);
                    log.debug("New config: {}", newConfig);
                    if (newConfig.isPresent()) {
                        return newConfig;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            log.debug("No valid config file found");
        }
        log.debug("Return empty config");
        return Optional.empty();
    }

    public ConfigChangeWatcher() throws IOException {
        log.info("creating watcher");
        // try to find valid config directories
        findValidConfigDir();


        this.watcher = DirectoryWatcher.builder()
                .paths(watchList)
                .fileHashing(false)
                .listener(event -> {
                    log.debug("Event: {}", event);
                    if (!event.path().getFileName().toString().equals(CONFIG_FILE_NAME)) {
                        log.debug("Event path: {}", event.path());
                        return;
                    }

                    switch (event.eventType()) {
                        case CREATE:
                        case MODIFY:
                            if (event.path().toFile().canRead()) {
                                log.debug("reading config file: {}", event.path());
                                var base64encodedjson = Files.readAllBytes(event.path());
                                var newConfig = Config.of(Arrays.copyOf(base64encodedjson, base64encodedjson.length-1), objectMapper);
                                if (newConfig.isPresent()) {
                                    log.debug("New config: {}", newConfig);
                                    if (this.currentConfig == null) {
                                        log.debug("Current config is null");
                                        // this means we will create the currentConfig
                                        this.currentConfig = newConfig.get();
                                        createBeans();
                                    } else {
                                        log.debug("Current config: {}", this.currentConfig);
                                        if (!this.currentConfig.getOrg().equals(newConfig.get().getOrg())) {
                                            // not the same org
                                            destroyBeans();
                                        } else {
                                            log.debug("New config has the same org {}", currentConfig.getOrg());
                                        }
                                        this.currentConfig = newConfig.get();
                                        createBeans();
                                    }
                                } else {
                                    log.debug("Can't read config file: {}", event.path());
                                }
                            } else {
                                log.error("Can't read config file {}", event.path());
                            }
                            break;
                        case DELETE:
                            if (this.currentConfig != null) {
                                destroyBeans();
                            }
                            break;
                        default:
                            log.info("Unexpected event: {}", event.eventType());
                            break;
                    }
                })
                .build();
    }


    private final static String gateClientPrefix = "gate-client-";
    private final static String gateClientJobStreamPrefix = "gate-client-job-stream-";
    private final static String gateClientPollEventsPrefix = "gate-client-poll-events-";
    private final static String gateClientConfigurationPrefix = "gate-client-config-";
    private void destroyBeans() {
        context.destroyBean(Argument.of(GateClient.class), Qualifiers.byName(gateClientPrefix + this.currentConfig.getOrg()));
        context.destroyBean(Argument.of(GateClientJobStream.class), Qualifiers.byName(gateClientJobStreamPrefix + this.currentConfig.getOrg()));
        context.destroyBean(Argument.of(GateClientPollEvents.class), Qualifiers.byName(gateClientPollEventsPrefix + this.currentConfig.getOrg()));
        context.destroyBean(Argument.of(GateClientConfiguration.class), Qualifiers.byName(gateClientConfigurationPrefix + this.currentConfig.getOrg()));
    }

    private void createBeans() {
        log.info("creating bean for org {}", this.currentConfig.getOrg());
        var gateClient = new GateClient(this.currentConfig);
        var demoPlugin = new DemoPlugin(gateClient);
        var gateClientJobStream = new GateClientJobStream(this.currentConfig, demoPlugin);

        var gateClientPollEvents = new GateClientPollEvents(this.currentConfig, demoPlugin);
        var gateClientConfiguration = new GateClientConfiguration(this.currentConfig, demoPlugin);

        context.registerSingleton(GateClient.class, gateClient, Qualifiers.byName(gateClientPrefix + this.currentConfig.getOrg()), true);
        context.registerSingleton(GateClientJobStream.class, gateClientJobStream,Qualifiers.byName(gateClientJobStreamPrefix + this.currentConfig.getOrg()), true);
        context.registerSingleton(GateClientPollEvents.class, gateClientPollEvents, Qualifiers.byName(gateClientPollEventsPrefix + this.currentConfig.getOrg()), true);
        context.registerSingleton(GateClientConfiguration.class, gateClientConfiguration, Qualifiers.byName(gateClientConfigurationPrefix + this.currentConfig.getOrg()), true);
        TaskScheduler taskScheduler = context.getBean(TaskScheduler.class);
        taskScheduler.scheduleAtFixedRate(Duration.ZERO, Duration.ofSeconds(1), gateClientJobStream::start);
        taskScheduler.scheduleAtFixedRate(Duration.ZERO, Duration.ofSeconds(10), gateClientPollEvents::start);
        taskScheduler.scheduleAtFixedRate(Duration.ZERO, Duration.ofSeconds(30), gateClientConfiguration::start);
        log.debug("test client-pool-events bean is created: {}",context.findBean(GateClientPollEvents.class, Qualifiers.byName("gate-client-poll-events-" + this.currentConfig.getOrg())).isPresent());
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        log.info("Starting config change watcher");
        getCurrentConfig().ifPresent(config -> {
            log.info("Current config: {}", config);
            this.currentConfig = config;
            createBeans();
        });
        this.watcher.watchAsync();
    }
}