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
package com.tcn.exile.singletenant;

import com.tcn.exile.gateclients.v2.GateClientConfiguration;
import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.plugin.PluginInterface;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "sati.tenant.type", value = "single")
public class SingleTenantGateClientConfiguration extends GateClientConfiguration {
    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SingleTenantGateClientConfiguration.class);

    @Inject
    PluginInterface plugin;

    @Inject
    ApplicationEventPublisher<PluginConfigEvent> eventPublisher;

    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        super.start();
    }

    // Helper method for single tenant implementation
    protected void publishEventSingleTenant(PluginConfigEvent event) {
        if (event != null) {
            log.debug("Publishing event in single tenant: {}", event);
            eventPublisher.publishEvent(event);
        }
    }
} 