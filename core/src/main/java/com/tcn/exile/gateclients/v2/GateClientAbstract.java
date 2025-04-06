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
package com.tcn.exile.gateclients.v2;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.ConfigInterface;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.micronaut.context.event.ApplicationEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public abstract class GateClientAbstract implements ApplicationEventListener<ConfigEventInterface> {
    private static final Logger log = LoggerFactory.getLogger(com.tcn.exile.gateclients.v2.GateClientAbstract.class);

    // Static channel shared by all instances, managed centrally
    private static volatile ManagedChannel sharedChannel;
    private static final Object channelLock = new Object(); // Lock for synchronized access

    // Use a static volatile field to hold the current config event for all instances
    // private static volatile ConfigEventInterface currentEvent;
    private static volatile ConfigInterface currentConfig;

    @Override
    public boolean supports(ConfigEventInterface event) {
        // We are interested in all non-null config events
        return event != null;
    }

    public void setConfig(ConfigInterface event) {
    }


    @Override
    public void onApplicationEvent(ConfigEventInterface event) {
        log.debug("Received ConfigEventInterface {}. Updating static config.", event);
        // Potentially shutdown existing channel if config changes significantly (e.g., endpoint)
        // Simple approach: always shutdown if a new valid event arrives
        if (currentConfig!= null && !currentConfig.isUnconfigured()) {
             // Optional: Add more sophisticated check to only shutdown if endpoint/creds change
             log.debug("Existing config found, shutting down channel before update.");
             shutdown();
        }
        // Update the static field, making it visible to all instances
        currentConfig= event.getConfig(); 
        // Trigger start/restart logic if necessary (may be handled by scheduler already)
        // Consider if start() needs explicit call here or if scheduler handles it.
        // start(); 
    }


    public Map<String,Object> getStatus() {
        // Read from static field
        ConfigInterface localEvent = this.currentConfig;
        if (isUnconfigured(localEvent)) {
            return Map.of(
                "running", false,
                "configured", !isUnconfigured(localEvent)
            );
        } else {
            ConfigInterface config = localEvent;
            return Map.of(
                "running", true,
                "configured", !isUnconfigured(localEvent),
                "api_endpoint", config.getApiEndpoint(),
                "org", config.getOrg(),
                "expiration_date", config.getExpirationDate(),
                "certificate_expiration_date", config.getExpirationDate(),
                "certificate_description", config.getCertificateDescription()
                );
        }
    }

    // Overload isUnconfigured to check the static field by default
    protected boolean isUnconfigured() {
        return isUnconfigured(currentConfig);
    }
    
    // Helper to check a specific event instance (used by getStatus)
    private boolean isUnconfigured(ConfigInterface eventToCheck) {
        if (eventToCheck == null) {
            return true;
        }
        try {
            return eventToCheck.isUnconfigured();
        } catch (Exception e) {
            // Log exception if needed
            return true;
        }
    }

    protected void shutdown() {
        log.debug("Attempting shutdown of static shared gRPC channel.");
        // Synchronize access to the shared channel for shutdown
        synchronized (channelLock) {
            ManagedChannel channelToShutdown = sharedChannel; // Work with local variable inside lock
            if ((channelToShutdown != null) && (!channelToShutdown.isShutdown() && !channelToShutdown.isTerminated())) {
                log.debug("Attempting graceful shutdown of shared channel {}", channelToShutdown);
                channelToShutdown.shutdown();
                try {
                    if (!channelToShutdown.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.warn("Shared channel {} did not terminate gracefully after 10s, forcing shutdown.", channelToShutdown);
                        channelToShutdown.shutdownNow();
                        if (!channelToShutdown.awaitTermination(5, TimeUnit.SECONDS)) {
                            log.error("Shared channel {} failed to terminate even after forced shutdown.", channelToShutdown);
                        }
                    }
                    log.info("Successfully shut down shared channel {}", channelToShutdown);
                } catch (InterruptedException e) {
                    log.error("Interrupted while waiting for shared channel shutdown, forcing shutdown now.", e);
                    channelToShutdown.shutdownNow();
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                }
                // Set static field to null AFTER successful shutdown
                sharedChannel = null; 
            } else {
                log.debug("Shared channel is already null, shut down, or terminated.");
                // Ensure static field is null if channel is unusable
                if (sharedChannel != null && (sharedChannel.isShutdown() || sharedChannel.isTerminated())) {
                   sharedChannel = null;
                }
            }
        }
    }
    
    public ManagedChannel getChannel() throws UnconfiguredException {
        ManagedChannel localChannel = sharedChannel; // Read volatile field once
        if ((localChannel != null) && !localChannel.isShutdown() && !localChannel.isTerminated()) {
            return localChannel;
        }
        
        // Synchronize channel creation using the static lock
        synchronized (channelLock) {
            // Double-check condition inside synchronized block
            localChannel = sharedChannel;
            if ((localChannel != null) && !localChannel.isShutdown() && !localChannel.isTerminated()) {
                return localChannel;
            }

            log.debug("Creating a new static shared gRPC channel.");
            ConfigInterface config = getConfig(); // Get current config from static field

            try {
                var channelCredentials = TlsChannelCredentials.newBuilder()
                    .trustManager(new ByteArrayInputStream(config.getRootCert().getBytes()))
                    .keyManager(
                        new ByteArrayInputStream(config.getPublicCert().getBytes()),
                        new ByteArrayInputStream(config.getPrivateKey().getBytes()))
                    .build();
                
                ManagedChannel newChannel = Grpc.newChannelBuilderForAddress(
                        config.getApiHostname(), config.getApiPort(), channelCredentials)
                    .keepAliveTime(1, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .idleTimeout(30, TimeUnit.MINUTES)
                    .overrideAuthority("exile-proxy")
                    .build();
                
                sharedChannel = newChannel; // Assign the new channel to the static field
                log.debug("New static shared channel created: {}", newChannel);
                return newChannel;
            } catch (IOException e) {
                log.error("IOException during shared channel creation", e);
                throw new UnconfiguredException("TCN Gate client configuration error during channel creation", e);
            } catch (UnconfiguredException e) {
                 log.error("Configuration error during shared channel creation", e);
                 throw e; // Re-throw specific unconfigured exception
            } catch (Exception e) {
                 log.error("Unexpected error during shared channel creation", e);
                 throw new UnconfiguredException("Unexpected error configuring TCN Gate client channel", e);
            }
        }
    }

    protected ConfigInterface getConfig() throws UnconfiguredException {
        // Read from static field
        ConfigInterface localEvent = currentConfig; 
        if (localEvent == null) {
             log.warn("getConfig() called but currentEvent is null");
             throw new UnconfiguredException("TCN Gate client is unconfigured (no event received)");
        }
        if (localEvent.isUnconfigured()) {
            throw new UnconfiguredException("TCN Gate client is unconfigured (event indicates unconfigured state)");
        }

        return localEvent;
    }

    public abstract void start();


    /**
     * Resets the gRPC channel after a connection failure
     */
    protected void resetChannel() {
        log.info("Resetting static shared gRPC channel after connection failure.");
        // Simply call the synchronized shutdown method which handles the static channel
        shutdown(); 
    }
    
    /**
     * Helper method to handle StatusRuntimeException
     * @param e The exception to handle
     * @return true if the exception was handled
     */
    protected boolean handleStatusRuntimeException(StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
            log.warn("Connection unavailable, resetting channel: {}", e.getMessage());
            resetChannel();
            return true;
        }
        return false;
    }
}
