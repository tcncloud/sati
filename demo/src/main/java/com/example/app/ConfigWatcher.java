package com.example.app;

import com.tcn.sati.config.SatiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.function.Consumer;

public class ConfigWatcher {
    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private final Path configFile;
    private final Consumer<SatiConfig> onConfigChange;
    private volatile boolean running = true;
    private Thread watcherThread;

    public ConfigWatcher(String configPath, Consumer<SatiConfig> onConfigChange) {
        this.configFile = Path.of(configPath);
        this.onConfigChange = onConfigChange;
    }

    public void start() {
        watcherThread = new Thread(this::watchLoop, "config-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("ConfigWatcher started for: {}", configFile);
    }

    private void watchLoop() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Path dir = configFile.getParent();
            if (dir == null) {
                dir = Path.of(".");
            }
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            while (running) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed.getFileName().equals(configFile.getFileName())) {
                        log.info("Config file changed, reloading...");
                        Thread.sleep(100);
                        try {
                            SatiConfig newConfig = Main.loadGateConfig(configFile.toString());
                            onConfigChange.accept(newConfig);
                        } catch (Exception e) {
                            log.error("Failed to reload config", e);
                        }
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            log.info("ConfigWatcher interrupted");
        } catch (Exception e) {
            log.error("ConfigWatcher error", e);
        }
    }

    public void stop() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }
}
