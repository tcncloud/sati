package com.tcn.exile.multi;

import com.tcn.exile.config.Config;
import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.ConfigInterface;
import com.tcn.exile.multitenant.Tenant;
import com.tcn.exile.multitenant.TenantFactory;
import io.micronaut.context.BeanContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST Controller for managing tenants.
 * Provides endpoints for creating, updating, and listing tenants.
 */
@Controller("/api/tenants")
public class MultitenantController {
    
    private static final Logger log = LoggerFactory.getLogger(MultitenantController.class);
    
    private final ApplicationEventPublisher<ConfigEvent> eventPublisher;
    private final BeanContext beanContext;
    
    /**
     * Constructor for MultitenantController.
     * 
     * @param eventPublisher The application event publisher
     * @param beanContext The bean context
     */
    @Inject
    public MultitenantController(ApplicationEventPublisher<ConfigEvent> eventPublisher, BeanContext beanContext) {
        this.eventPublisher = eventPublisher;
        this.beanContext = beanContext;
        log.info("MultitenantController initialized");
    }
    
    /**
     * Creates a new tenant.
     * 
     * @param tenantId The tenant identifier
     * @param configData The tenant configuration data
     * @return HTTP response with the result
     */
    @Post("/{tenantId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createTenant(@PathVariable String tenantId, @Body Map<String, Object> configData) {
        log.info("Received request to create tenant: {}", tenantId);
        
        try {
            // Check if tenant already exists
            try {
                beanContext.getBean(Tenant.class, Qualifiers.byName(tenantId));
                return HttpResponse.badRequest().body(Map.of(
                    "success", false,
                    "message", "Tenant already exists: " + tenantId
                ));
            } catch (Exception e) {
                // Tenant doesn't exist, proceed with creation
                log.debug("Tenant {} does not exist, creating...", tenantId);
                
                // Build Config object from request body
                Config config = buildConfigFromMap(configData);
                
                // Validate tenantId consistency if needed
                String orgFromConfig = config.getOrg();
                if (orgFromConfig != null && !orgFromConfig.equals(tenantId)) {
                    log.warn("Tenant ID in path ('{}') differs from org in config ('{}'). Using org from config.", tenantId, orgFromConfig);
                    // Decide if this should be an error or just a warning
                } else if (orgFromConfig == null) {
                    log.warn("Org ID (CN) missing in provided config for tenant '{}'.", tenantId);
                    // Decide if this is acceptable
                }

                // Publish the standard ConfigEvent
                ConfigEvent event = new ConfigEvent(this, config, ConfigEventInterface.EventType.CREATE);
                eventPublisher.publishEvent(event);
                
                return HttpResponse.created(Map.of(
                    "success", true,
                    "tenantId", tenantId, // Return the path tenantId for consistency with request
                    "message", "Tenant creation initiated successfully"
                ));
            }
        } catch (IllegalArgumentException iae) {
             log.error("Error processing config data for tenant {}: {}", tenantId, iae.getMessage(), iae);
             return HttpResponse.badRequest().body(Map.of(
                "success", false,
                "message", "Invalid configuration data: " + iae.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error creating tenant {}: {}", tenantId, e.getMessage(), e);
            return HttpResponse.serverError().body(Map.of(
                "success", false,
                "message", "Error creating tenant: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Updates an existing tenant.
     * 
     * @param tenantId The tenant identifier
     * @param configData The updated tenant configuration data
     * @return HTTP response with the result
     */
    @Put("/{tenantId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateTenant(@PathVariable String tenantId, @Body Map<String, Object> configData) {
        log.info("Received request to update tenant: {}", tenantId);
        
        try {
            // Check if tenant exists
            Tenant tenant = beanContext.getBean(Tenant.class, Qualifiers.byName(tenantId));
            log.debug("Found existing tenant bean for {}, proceeding with update...", tenantId);

            // Build Config object from request body
            Config config = buildConfigFromMap(configData);
            
            // Optional: Validate tenantId consistency
            String orgFromConfig = config.getOrg();
            if (orgFromConfig != null && !orgFromConfig.equals(tenantId)) {
                log.warn("Tenant ID in path ('{}') differs from org in config ('{}') during update. Using org from config.", tenantId, orgFromConfig);
            } else if (orgFromConfig == null) {
                 log.warn("Org ID (CN) missing in provided update config for tenant '{}'.", tenantId);
            }

            // Publish the standard ConfigEvent
            ConfigEvent event = new ConfigEvent(this, config, ConfigEventInterface.EventType.UPDATE);
            eventPublisher.publishEvent(event);
            
            return HttpResponse.ok(Map.of(
                "success", true,
                "tenantId", tenantId,
                "message", "Tenant update initiated successfully"
            ));
            
        } catch (io.micronaut.context.exceptions.NoSuchBeanException nsbe) {
            // Tenant doesn't exist
             log.warn("Update requested for non-existent tenant: {}", tenantId);
             return HttpResponse.notFound().body(Map.of(
                 "success", false,
                 "message", "Tenant not found: " + tenantId
             ));
        } catch (IllegalArgumentException iae) {
             log.error("Error processing update config data for tenant {}: {}", tenantId, iae.getMessage(), iae);
             return HttpResponse.badRequest().body(Map.of(
                "success", false,
                "message", "Invalid configuration data: " + iae.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error updating tenant {}: {}", tenantId, e.getMessage(), e);
            return HttpResponse.serverError().body(Map.of(
                "success", false,
                "message", "Error updating tenant: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Deletes a tenant.
     * 
     * @param tenantId The tenant identifier
     * @return HTTP response with the result
     */
    @Delete("/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> deleteTenant(@PathVariable String tenantId) {
        log.info("Received request to delete tenant: {}", tenantId);
        
        try {
            // Check if tenant exists and get the bean
            Tenant tenant = beanContext.getBean(Tenant.class, Qualifiers.byName(tenantId));
            log.debug("Found tenant bean for {}, proceeding with deletion...", tenantId);
            
            // Destroy the bean directly
            beanContext.destroyBean(tenant);
            log.info("Successfully destroyed tenant bean: {}", tenantId);
            
            // No event publishing needed here as we handle destruction directly
            
            return HttpResponse.ok(Map.of(
                "success", true,
                "tenantId", tenantId,
                "message", "Tenant deleted successfully"
            ));

        } catch (io.micronaut.context.exceptions.NoSuchBeanException nsbe) {
            // Tenant doesn't exist
             log.warn("Delete requested for non-existent tenant: {}", tenantId);
             return HttpResponse.notFound().body(Map.of(
                 "success", false,
                 "message", "Tenant not found: " + tenantId
             ));
        } catch (Exception e) {
            log.error("Error deleting tenant {}: {}", tenantId, e.getMessage(), e);
            return HttpResponse.serverError().body(Map.of(
                "success", false,
                "message", "Error deleting tenant: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Lists all tenants.
     * 
     * @return HTTP response with list of tenant IDs
     */
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> listTenants() {
        log.info("Received request to list all tenants");
        
        try {
            Map<String, Tenant> tenants = beanContext.getBeansOfType(Tenant.class)
                .stream()
                .collect(Collectors.toMap(
                    Tenant::getTenantId, 
                    tenant -> tenant
                ));
            
            Map<String, ConfigInterface> tenantConfigs = new HashMap<>();
            
            for (Map.Entry<String, Tenant> entry : tenants.entrySet()) {
                tenantConfigs.put(entry.getKey(), entry.getValue().getConfig());
            }
            
            return HttpResponse.ok(Map.of(
                "success", true,
                "count", tenants.size(),
                "tenants", tenantConfigs
            ));
        } catch (Exception e) {
            log.error("Error listing tenants: {}", e.getMessage(), e);
            return HttpResponse.serverError().body(Map.of(
                "success", false,
                "message", "Error listing tenants: " + e.getMessage()
            ));
        }
    }
    
    // Helper method to build Config from Map
    private Config buildConfigFromMap(Map<String, Object> data) throws IllegalArgumentException {
        try {
            Config.Builder builder = Config.builder();
            
            // Map known fields, handling potential nulls and types
            if (data.containsKey("rootCert")) builder.rootCert((String) data.get("rootCert"));
            if (data.containsKey("publicCert")) builder.publicCert((String) data.get("publicCert"));
            if (data.containsKey("privateKey")) builder.privateKey((String) data.get("privateKey"));
            if (data.containsKey("fingerprintSha256")) builder.fingerprintSha256((String) data.get("fingerprintSha256"));
            if (data.containsKey("fingerprintSha256String")) builder.fingerprintSha256String((String) data.get("fingerprintSha256String"));
            if (data.containsKey("apiEndpoint")) builder.apiEndpoint((String) data.get("apiEndpoint"));
            if (data.containsKey("certificateName")) builder.certificateName((String) data.get("certificateName"));
            if (data.containsKey("certificateDescription")) builder.certificateDescription((String) data.get("certificateDescription"));
            // Explicitly handle unconfigured state if provided, otherwise builder defaults might apply
            if (data.containsKey("unconfigured")) builder.unconfigured((Boolean) data.get("unconfigured")); 
            
            return builder.build();
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Invalid data type in configuration map", cce);
        } catch (Exception e) {
             throw new IllegalArgumentException("Failed to build config from map: " + e.getMessage(), e);
        }
    }
} 