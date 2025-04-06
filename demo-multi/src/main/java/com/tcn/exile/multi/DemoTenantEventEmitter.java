package com.tcn.exile.multi;

import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.ConfigInterface;
import com.tcn.exile.multitenant.TenantFactory;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bean that emits TenantConfigEvent for four tenant IDs at regular intervals.
 * This is useful for testing and demo purposes.
 */
@Singleton
public class DemoTenantEventEmitter {
    
    private static final Logger log = LoggerFactory.getLogger(DemoTenantEventEmitter.class);
    
    // Tenant IDs to emit events for
    private static final String TENANT_ID_1 = "tenant-demo-1";
    private static final String TENANT_ID_2 = "tenant-demo-2";
    private static final String TENANT_ID_3 = "tenant-demo-3";
    private static final String TENANT_ID_4 = "tenant-demo-4";
    
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicInteger updateCounter = new AtomicInteger(0);
    
    // Track if tenants have been created
    private final Map<String, Boolean> tenantsCreated = new HashMap<>();
    
    /**
     * Constructor for DemoTenantEventEmitter.
     * 
     * @param eventPublisher The application event publisher
     */
    @Inject
    public DemoTenantEventEmitter(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        // Initialize all tenants as not created
        tenantsCreated.put(TENANT_ID_1, false);
        tenantsCreated.put(TENANT_ID_2, false);
        tenantsCreated.put(TENANT_ID_3, false);
        tenantsCreated.put(TENANT_ID_4, false);
        log.info("DemoTenantEventEmitter initialized for 4 tenants");
    }
    
    /**
     * Startup event listener - logs when application is ready
     */
    @EventListener
    public void onStartup(StartupEvent event) {
        log.info("DemoTenantEventEmitter received startup event - ready to create tenants");
        

    }
    

    
    /**
     * Create a tenant if it hasn't been created yet.
     * 
     * @param tenantId The ID of the tenant to create
     */
    private void createTenantIfNeeded(String tenantId) {

    }
    
    /**
     * Emits update events for all tenants at varying intervals.
     */
    @Scheduled(initialDelay = "15s", fixedDelay = "20s")
    public void updateTenants() {

    }
    
    /**
     * Creates a sample configuration map.
     * 
     * @param tenantId The tenant ID
     * @return A sample configuration map
     */
    private Config createSampleConfig(String tenantId) {
        // build a sample config with random uuid 
        var config = Config.builder().apiEndpoint("https://api.example.com")
        .certificateDescription("")
        .certificateName("")
        .fingerprintSha256("")
        .fingerprintSha256String("")
        .privateKey("")
        .publicCert("")
        .rootCert("")
        .build();

        return config;
    }
    
    /**
     * Emits a tenant configuration event.
     * 
     * @param tenantId The tenant ID
     * @param eventType The event type (CREATE, UPDATE, DELETE)
     * @param configData The configuration data
     */
    private void emitEvent(String tenantId, String eventType, Map<String, Object> configData) {

    }
} 