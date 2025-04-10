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

import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.StatusRuntimeException;
import io.micronaut.scheduling.annotation.Scheduled;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.GetClientConfigurationRequest;
import tcnapi.exile.gate.v2.Public.GetClientConfigurationResponse;

import java.util.concurrent.TimeUnit;

public class GateClientConfiguration extends GateClientAbstract {
    PluginConfigEvent event = null;

    PluginInterface plugin;


    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientConfiguration.class);

    public GateClientConfiguration(String tenant, Config currentConfig, PluginInterface plugin) {
        super(tenant, currentConfig);
        this.plugin = plugin;
    }


    @Override
    @Scheduled(fixedDelay = "10s")
    public void start() {
        try {
            var client = GateServiceGrpc.newBlockingStub(getChannel())
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .withWaitForReady();
            GetClientConfigurationResponse response = client.getClientConfiguration(GetClientConfigurationRequest.newBuilder().build());
            var newEvent = new PluginConfigEvent(this)
                    .setConfigurationName(response.getConfigName())
                    .setConfigurationPayload(response.getConfigPayload())
                    .setOrgId(response.getOrgName())
                    .setOrgName(response.getOrgName())
                    .setUnconfigured(false);
            if (!newEvent.equals(event)) {
                log.debug("Tenant: {} - Received new configuration, update plugin config", tenant);
                event = newEvent;
                this.plugin.setConfig(event);
            }
        } catch (UnconfiguredException e) {
            log.debug("Tenant: {} - Configuration not set, skipping get client configuration", tenant);
        } catch (StatusRuntimeException e) {
            log.error("Tenant: {} - Failed to get client configuration: {}", tenant, e.getMessage());
        }
    }
}
