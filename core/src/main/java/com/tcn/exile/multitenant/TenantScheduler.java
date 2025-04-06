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

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Scheduler responsible for periodically executing the run method on each tenant.
 */
@Singleton
@Context
@Requires(property = "sati.tenant.type", value = "multi")
public class TenantScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(TenantScheduler.class);
    
    private final TenantFactory tenantFactory;
    
    /**
     * Constructor for TenantScheduler.
     * 
     * @param tenantFactory The factory that manages tenant instances
     */
    @Inject
    public TenantScheduler(TenantFactory tenantFactory) {
        this.tenantFactory = tenantFactory;
        log.info("TenantScheduler initialized");
    }
    
    /**
     * Scheduled task that runs every 10 seconds.
     * Executes the run method for each tenant.
     */
    @Scheduled(fixedDelay = "10s")
    public void executeTenantsRun() {
        log.info("Executing scheduled task for tenants");
        try {
            Map<String, Tenant> tenants = getSafeTenants();
            
            if (tenants.isEmpty()) {
                log.debug("No tenants available to run scheduled task");
                return;
            }
            
            log.debug("Running scheduled task for {} tenants", tenants.size());
            
            // Execute run method for each tenant
            for (Tenant tenant : tenants.values()) {
                try {
                    tenant.run();
                } catch (Exception e) {
                    log.error("Error executing scheduled run for tenant {}: {}", 
                            tenant.getTenantId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error in tenant scheduler: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Safely gets tenants, with error handling.
     * 
     * @return Map of tenant IDs to tenants, or empty map on error
     */
    private Map<String, Tenant> getSafeTenants() {
        try {
            Map<String, Tenant> tenants = tenantFactory.getAllTenants();
            return tenants != null ? tenants : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Error getting tenants: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
} 