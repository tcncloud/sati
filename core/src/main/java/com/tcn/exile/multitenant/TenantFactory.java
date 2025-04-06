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

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.ConfigInterface;
import com.tcn.exile.gateclients.v2.GateClientJobStream;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Requires;
/**
 * Factory class responsible for creating and managing tenant-specific beans.
 * Listens for ConfigEventInterface events and processes them accordingly.
 */
@Factory
@Singleton
@Requires(property = "sati.tenant.type", value = "multi")
public class TenantFactory implements ApplicationEventListener<ConfigEventInterface> {
    
    private static final Logger log = LoggerFactory.getLogger(TenantFactory.class);
    
    private final BeanContext beanContext;
    
    /**
     * Constructor for TenantFactory.
     * 
     * @param beanContext The bean context for registering beans
     */
    @Inject
    public TenantFactory(BeanContext beanContext) {
        this.beanContext = beanContext;
        log.info("Initializing TenantFactory");
    }
    
    @PostConstruct
    void initialize() {
        log.info("TenantFactory PostConstruct: Checking beans...");
        logGateClientJobStreamBeans("PostConstruct");
    }

    private void logGateClientJobStreamBeans(String phase) {
        try {
            Collection<GateClientJobStream> jobStreamBeans = beanContext.getBeansOfType(GateClientJobStream.class);
            log.info("[{}] Found {} beans of type GateClientJobStream:", phase, jobStreamBeans.size());
            int count = 0;
            for (GateClientJobStream bean : jobStreamBeans) {
                count++;
                log.info("  Bean {}: Class = {}", count, bean.getClass().getName());
            }
            if (jobStreamBeans.isEmpty()) {
                 log.warn("[{}] No beans of type GateClientJobStream found.", phase);
            }
        } catch (Exception e) {
            log.error("[{}] Error retrieving GateClientJobStream beans: {}", phase, e.getMessage(), e);
        }
    }
    
    /**
     * Determines if this listener supports the given event.
     *
     * @param event The event to check
     * @return true if the event is supported, false otherwise
     */
    @Override
    public boolean supports(ConfigEventInterface event) {
        return event != null;
    }
    
    /**
     * Handles the configuration event by creating, updating, or removing tenant beans.
     *
     * @param event The event to process
     */
    @Override
    public void onApplicationEvent(ConfigEventInterface event) {
        if (event == null) {
            log.warn("Received null configuration event");
            return;
        }
        
        ConfigInterface config = event.getConfig();
        String tenantId = config.getOrg();
        log.info("Processing configuration event for tenant: {}", tenantId);
        
        try {
            var eventType = event.getEventType();
            

            
            switch (eventType) {
                case CREATE:
                case UPDATE:
                    // Get or create the tenant bean
                    try {
                        Tenant tenant = beanContext.getBean(Tenant.class, Qualifiers.byName(tenantId));
                        // Update existing tenant config
                        log.info("Updating existing tenant: {}", tenantId);
                        tenant.onConfigEvent(event);
                    } catch (Exception e) {
                        // Tenant doesn't exist, create a new one

                        log.info("Creating new tenant: {}", tenantId);
                        Tenant newTenant = new TenantImpl(tenantId, config);
                        beanContext.registerSingleton(
                            Tenant.class,
                            newTenant,
                            Qualifiers.byName(tenantId),
                            true
                        );
                    }
                    break;
                    
                case DELETE:
                    try {
                        // Try to get the bean
                        Tenant tenant = beanContext.getBean(Tenant.class, Qualifiers.byName(tenantId));
                        
                        // Destroy the bean if it exists
                        log.info("Destroying tenant bean: {}", tenantId);
                        beanContext.destroyBean(tenant);
                        log.info("Successfully destroyed tenant: {}", tenantId);
                    } catch (Exception e) {
                        log.warn("Failed to destroy tenant {}: {}", tenantId, e.getMessage());
                    }
                    break;
                    
                default:
                    log.warn("Unknown event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Error processing configuration event for tenant {}: {}", 
                    tenantId, e.getMessage(), e);
        }
    }
    
    /**
     * Gets all tenants.
     *
     * @return Map of tenant IDs to tenants
     */
    public Map<String, Tenant> getAllTenants() {
        Map<String, Tenant> tenants = new ConcurrentHashMap<>();
        
        try {
            // Find all bean definitions of type Tenant
            Collection<BeanDefinition<Tenant>> beanDefinitions = 
                beanContext.getBeanDefinitions(Tenant.class);
            
            // Get the actual bean instances
            for (BeanDefinition<Tenant> definition : beanDefinitions) {
                try {
                    Tenant tenant = beanContext.getBean(definition);
                    String tenantId = tenant.getTenantId();
                    tenants.put(tenantId, tenant);
                } catch (Exception e) {
                    log.warn("Failed to get tenant bean: {}", 
                        e.getMessage());
                }
            }
            
            log.debug("Found {} tenant beans", tenants.size());
        } catch (Exception e) {
            log.error("Error retrieving tenant beans: {}", e.getMessage());
        }
        
        return tenants;
    }
    
  
} 