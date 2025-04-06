package com.tcn.exile.multi;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application class for the multitenant demo.
 * Demonstrates how to use the multitenant functionality in a separate application.
 */
public class MultitenantApplication {
    
    private static final Logger log = LoggerFactory.getLogger(MultitenantApplication.class);
    
    public static void main(String[] args) {
        // log.info("Starting Multitenant Demo Application");
        // Micronaut.run(MultitenantApplication.class, args);
        
        try {
            // Configure application with more verbose error handling
            log.info("Starting Multitenant Demo Application");
            ApplicationContext context = Micronaut.build(args)
                .banner(true)  // Enable banner for cleaner output
                .mainClass(MultitenantApplication.class)
                .start();
            
            log.info("Application context started successfully. Bean count: {}", 
                    context.getBeanDefinitions(Object.class).size());
            
            // Check the property value directly
            log.info("Checking environment property value for sati.tenant.type: '{}'", 
                     context.getEnvironment().getProperty("sati.tenant.type", String.class, "NOT SET"));
            
            // Log available beans for debugging
            log.debug("Available beans:");
            context.getBeanDefinitions(Object.class).forEach(bean -> 
                log.debug(" - {} ({})", bean.getName(), bean.getBeanType().getName()));
            
            // Try to get the watcher bean directly
            log.info("Checking if MultiTenantConfigWatcher is available as a bean...");
            boolean hasWatcherBean = context.containsBean(com.tcn.exile.multi.config.MultiTenantConfigWatcher.class);
            log.info("MultiTenantConfigWatcher bean exists: {}", hasWatcherBean);
            
            if (hasWatcherBean) {
                Object watcher = context.getBean(com.tcn.exile.multi.config.MultiTenantConfigWatcher.class);
                log.info("Successfully retrieved watcher bean: {}", watcher);
            }
            
            // Keep application running
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("Error starting application: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
} 