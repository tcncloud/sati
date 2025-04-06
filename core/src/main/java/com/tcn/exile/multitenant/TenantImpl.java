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
package com.tcn.exile.multitenant;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.ConfigInterface;
import com.tcn.exile.gateclients.v2.GateClient;
import com.tcn.exile.gateclients.v2.GateClientJobStream;
import com.tcn.exile.gateclients.v2.GateClientPollEvents;

import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.context.annotation.Requires;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of the Tenant interface.
 * Each instance represents a separate tenant in the multi-tenant system.
 */
@Singleton
@Requires(property = "sati.tenant.type", value = "never")
public class TenantImpl implements Tenant {
    
    private static final Logger log = LoggerFactory.getLogger(TenantImpl.class);
    
    private final String tenantId;
    private final AtomicLong counter = new AtomicLong(0);
    private ConfigInterface config; 
    
    private final GateClientJobStream jobStream;
    private final GateClientPollEvents pollEvents;
    private final GateClient gateClient;
    /**
     * Constructor for TenantImpl.
     * 
     * @param tenantId The tenant identifier
     * @param config Initial configuration
     */
    public TenantImpl(String tenantId, ConfigInterface config) {
        this.tenantId = tenantId;
        this.config  = config;
        this.jobStream = new GateClientJobStream();
        this.pollEvents = new GateClientPollEvents();
        this.gateClient = new GateClient();
        this.jobStream.setConfig(config);
        this.jobStream.setConfig(config);
        this.jobStream.setConfig(config);

        log.info("Created tenant instance for tenant: {}", tenantId);
    }
    
    @Override
    public void run() {
        long count = counter.incrementAndGet();
        log.info("Running scheduled task for tenant: {}, execution count: {}", tenantId, count);
        
        // Perform tenant-specific periodic operations here
        // This could include health checks, data processing, etc.
        try {
            synchronized (this) {
            }
        } catch (Exception e) {
            log.error("Error during tenant {} scheduled run: {}", tenantId, e.getMessage(), e);
        }
    }


    
    @Override
    public String getTenantId() {
        return tenantId;
    }
    
    @Override
    public synchronized ConfigInterface getConfig() {
        return config;
    }
    
    @Scheduled(fixedDelay = "10s")
    public void runEveny10s() {
        log.info("Running every 10s scheduled task for tenant: {}", tenantId);
    }
    @Scheduled(fixedDelay = "1s")
    public void runEveny1s() {
        log.info("Running every 1s scheduled task for tenant: {}", tenantId);
    }

    @Override
    public void onConfigEvent(ConfigEventInterface event) {
        log.info("Received config event {} for tenant: {}", event.getEventType(), tenantId);
        if (!tenantId.equals(event.getConfig().getOrg())) {
            log.debug("Config event is for a different tenant: {}", event.getConfig().getOrg());
            return;
        }

        switch (event.getEventType()) {
            case CREATE:
                this.config = event.getConfig();
                break;
            case UPDATE:
                this.config = event.getConfig();
                break;
            case DELETE:
                this.config = null;
                break;
        }
        reconfigure();
    }

    private void reconfigure() {
        log.info("Reconfiguring tenant: {}", tenantId);
    }
}