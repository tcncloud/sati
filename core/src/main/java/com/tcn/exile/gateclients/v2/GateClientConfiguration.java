/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
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
 *
 */
package com.tcn.exile.gateclients.v2;

import build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc;
import build.buf.gen.tcnapi.exile.gate.v2.GetClientConfigurationRequest;
import build.buf.gen.tcnapi.exile.gate.v2.GetClientConfigurationResponse;
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.PluginConfigEvent;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.StatusRuntimeException;
import io.micronaut.scheduling.annotation.Scheduled;
import java.util.concurrent.TimeUnit;

public class GateClientConfiguration extends GateClientAbstract {
  PluginConfigEvent event = null;

  PluginInterface plugin;

  protected static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GateClientConfiguration.class);

  public GateClientConfiguration(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
    this.event = new PluginConfigEvent(this).setOrgId(currentConfig.getOrg()).setUnconfigured(true);
  }

  @Override
  @Scheduled(fixedDelay = "10s")
  public void start() {
    try {
      var client =
          GateServiceGrpc.newBlockingStub(getChannel())
              .withDeadlineAfter(300, TimeUnit.SECONDS)
              .withWaitForReady();
      GetClientConfigurationResponse response =
          client.getClientConfiguration(GetClientConfigurationRequest.newBuilder().build());

      log.debug(
          "Tenant: {} got config response: {} current event: {}",
          this.tenant,
          response,
          this.event);
      var newEvent =
          new PluginConfigEvent(this)
              .setConfigurationName(response.getConfigName())
              .setConfigurationPayload(response.getConfigPayload())
              .setOrgId(response.getOrgId())
              .setOrgName(response.getOrgName())
              .setUnconfigured(false);
      if (!newEvent.equals(event)) {
        log.debug(
            "Tenant: {} - Received new configuration event: {}, update plugin config",
            tenant,
            newEvent);
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
