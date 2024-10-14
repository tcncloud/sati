package com.tcn.exile.config;

import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;

@Singleton
public class ConfigChangeEventListener  implements ApplicationEventListener<ConfigEvent>{

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ConfigChangeEventListener.class);

    @Override
    public void onApplicationEvent(ConfigEvent event) {
        log.info("Config change event: {}", event);
        // throw new UnsupportedOperationException("Unimplemented method 'onApplicationEvent'");
    }
    // @EventListener
    // public void onConfigChange(ConfigChangeEvent changeEvent) {
    //     log.info("Config change event: {}", changeEvent);
    // }
}