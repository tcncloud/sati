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
import build.buf.gen.tcnapi.exile.gate.v2.PollEventsRequest;
import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.StatusRuntimeException;
import io.micronaut.scheduling.annotation.Scheduled;
import java.util.concurrent.TimeUnit;

public class GateClientPollEvents extends GateClientAbstract {
  protected static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GateClientPollEvents.class);

  PluginInterface plugin;

  public GateClientPollEvents(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant, currentConfig);
    this.plugin = plugin;
  }

  @Override
  @Scheduled(fixedDelay = "10s")
  public void start() {
    try {
      if (isUnconfigured()) {
        log.debug("Tenant: {} - Configuration not set, skipping poll events", tenant);
        return;
      }
      if (!plugin.isRunning()) {
        log.debug(
            "Tenant: {} - Plugin is not running (possibly due to database disconnection), skipping poll events",
            tenant);
        return;
      }
      var client =
          GateServiceGrpc.newBlockingStub(getChannel())
              .withDeadlineAfter(300, TimeUnit.SECONDS)
              .withWaitForReady();
      var response = client.pollEvents(PollEventsRequest.newBuilder().build());
      if (response.getEventsCount() == 0) {
        log.debug(
            "Tenant: {} - Poll events request completed successfully but no events were received",
            tenant);
        return;
      }
      long start = System.currentTimeMillis();
      response
          .getEventsList()
          .forEach(
              event -> {
                if (event.hasAgentCall()) {
                  log.debug(
                      "Tenant: {} - Received agent call event {} - {}",
                      tenant,
                      event.getAgentCall().getCallSid(),
                      event.getAgentCall().getCallType());
                  plugin.handleAgentCall(event.getAgentCall());
                }
                if (event.hasAgentResponse()) {
                  log.debug(
                      "Tenant: {} - Received agent response event {}",
                      tenant,
                      event.getAgentResponse().getAgentCallResponseSid());
                  plugin.handleAgentResponse(event.getAgentResponse());
                }

                if (event.hasTelephonyResult()) {
                  log.debug(
                      "Tenant: {} - Received telephony result event {} - {}",
                      tenant,
                      event.getTelephonyResult().getCallSid(),
                      event.getTelephonyResult().getCallType());
                  plugin.handleTelephonyResult(event.getTelephonyResult());
                }

                if (event.hasTransferInstance()) {
                  log.debug(
                      "Tenant: {} - Received transfer instance event {}",
                      tenant,
                      event.getTransferInstance().getTransferInstanceId());
                  plugin.handleTransferInstance(event.getTransferInstance());
                }

                if (event.hasCallRecording()) {
                  log.debug(
                      "Tenant: {} - Received call recording event {}",
                      tenant,
                      event.getCallRecording().getId());
                  plugin.handleCallRecording(event.getCallRecording());
                }
              });
      long end = System.currentTimeMillis();
      // if we take longer than 1 second on average to process an event, log something
      if (response.getEventsCount() > 0) {
        long avg = (end - start) / response.getEventsCount();
        if (avg > 1000) {
          log.warn(
              "Tenant: {} - Poll events request completed {} events successfully in {}ms, average time per event: {}ms, this is long",
              tenant,
              response.getEventsCount(),
              end - start,
              avg);
        }
      }
    } catch (StatusRuntimeException e) {
      if (handleStatusRuntimeException(e)) {
        // Already handled in parent class method
      } else {
        log.error("Tenant: {} - Error in poll events: {}", tenant, e.getMessage());
      }
    } catch (UnconfiguredException e) {
      log.error("Tenant: {} - Error while getting client configuration {}", tenant, e.getMessage());
    } catch (Exception e) {
      log.error("Tenant: {} - Unexpected error in poll events", tenant, e);
    }
  }
}
