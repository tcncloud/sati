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
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.scheduling.annotation.Scheduled;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.StreamJobsRequest;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class GateClientJobStream extends GateClientAbstract
    implements StreamObserver<tcnapi.exile.gate.v2.Public.StreamJobsResponse> {
  protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientJobStream.class);

  PluginInterface plugin;

  private int reconnectAttempt = 0;
  private static final int MAX_RECONNECT_DELAY_SECONDS = 60;

  private final Lock streamLock = new ReentrantLock();

  private GateServiceGrpc.GateServiceStub client;

  public GateClientJobStream(String tenant, Config currentConfig, PluginInterface plugin) {
    super(tenant,currentConfig);
    this.plugin = plugin;
  }

  @Override
  @Scheduled(fixedDelay = "1s")
  public void start() {
    log.trace("Tenant: {} - start", tenant);
    if (streamLock.tryLock()) {
      if (isUnconfigured()) {
        log.debug("Tenant: {} - Configuration not set, skipping job stream", tenant);
        streamLock.unlock();
        return;
      }
      if (!plugin.isRunning()) {
        log.debug("Tenant: {} - Plugin is not running (possibly due to database disconnection), skipping job stream", tenant);
        streamLock.unlock();
        return;
      }
      if (isRunning()) {
        log.trace("Tenant: {} - Job stream already running, skipping start", tenant);
        streamLock.unlock();
        return;
      }

      // Check if we already have an active stream
      // Log immediately after acquiring the lock
      log.debug("Tenant: {} - Thread {} acquired streamLock.", tenant, Thread.currentThread().getName());
      try {
        log.debug("Tenant: {} - Attempting to start job stream", tenant);
        // THIS WAS ISSUING A SHUTDOWN ON THE STATIC CHANNEL AND CAUSING ALL JOB STREAMS TO STOP
        // if (!isRunning()) {
        //   log.debug("Tenant: {} - is not running then attempting to shutdown job stream", tenant);
        //   shutdown(); // shutdown() acts on static channel
        // }
        
        // Use getChannel() directly here
        this.client = GateServiceGrpc.newStub(getChannel())
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .withWaitForReady();
        this.client.streamJobs(StreamJobsRequest.newBuilder().build(), this);
      } catch (StatusRuntimeException e) {
        if (handleStatusRuntimeException(e)) {
          log.warn("Tenant: {} - Connection unavailable in job stream, channel reset", tenant);
        } else {
          log.error("Tenant: {} - Error in job stream: {} ({})", tenant, e.getMessage(), e.getStatus().getCode());
        }
      } catch (UnconfiguredException e) {
        log.error("Tenant: {} - Error while starting job stream {}", tenant, e.getMessage());
      } catch (Exception e) {
        log.error("Tenant: {} - Unexpected error in job stream", tenant, e);
      } finally {
        streamLock.unlock();
      }
    } else {
      log.debug("Tenant: {} - streamLock already locked, skipping start", tenant);
    }
  }

  public boolean isRunning() {
    return client != null ;
  }

  @Override
  public void onNext(StreamJobsResponse value) {
    log.debug("Tenant: {} - Received {} job", tenant, value.getJobId());
    try {
      if (value.hasListPools()) {
        plugin.listPools(value.getJobId(), value.getListPools());
      } else if (value.hasGetPoolStatus()) {
        plugin.getPoolStatus(value.getJobId(), value.getGetPoolStatus());
      } else if (value.hasGetPoolRecords()) {
        plugin.getPoolRecords(value.getJobId(), value.getGetPoolRecords());
      } else if (value.hasSearchRecords()) {
        plugin.searchRecords(value.getJobId(), value.getSearchRecords());
      } else if (value.hasGetRecordFields()) {
        plugin.readFields(value.getJobId(), value.getGetRecordFields());
      } else if (value.hasSetRecordFields()) {
        plugin.writeFields(value.getJobId(), value.getSetRecordFields());
      } else if (value.hasCreatePayment()) {
        plugin.createPayment(value.getJobId(), value.getCreatePayment());
      } else if (value.hasPopAccount()) {
        plugin.popAccount(value.getJobId(), value.getPopAccount());
      } else if (value.hasInfo()) {
        plugin.info(value.getJobId(), value.getInfo());
      } else if (value.hasShutdown()) {
        plugin.shutdown(value.getJobId(), value.getShutdown());
      } else if (value.hasLogging()) {
        plugin.logger(value.getJobId(), value.getLogging());
      } else if (value.hasExecuteLogic()) {
        plugin.executeLogic(value.getJobId(), value.getExecuteLogic());
      } else {
        log.error("Tenant: {} - Unknown job type {}", tenant, value.getUnknownFields());
      }

    } catch (UnconfiguredException e) {
      log.error("Tenant: {} - Error while handling job {}", tenant, value.getJobId());
    }
  }

  @Override
  public void onError(Throwable t) {
    // Log the error *before* changing the state
    if (t instanceof StatusRuntimeException) {
      StatusRuntimeException statusEx = (StatusRuntimeException) t;
      log.error("Tenant: {} - Job stream onError: Status={}, Message={}", tenant, statusEx.getStatus(), statusEx.getMessage(), statusEx);
      // Resetting flag *only after logging*
      if (statusEx.getStatus().getCode() == Status.Code.CANCELLED) {
        log.warn("Tenant: {} - Stream cancelled by server or network issue, attempting reconnect...", tenant);
        // Add a delay to prevent immediate reconnection flooding
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        reconnectStream(); // Attempt reconnect
      } else if (statusEx.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        log.warn("Tenant: {} - Stream unavailable, likely network issue or server restart. Attempting reconnect...", tenant);
        // Add a delay
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        reconnectStream(); // Attempt reconnect (resetChannel is called within)
      } else {
        log.error("Tenant: {} - Unhandled StatusRuntimeException in job stream. Status: {}, Message: {}", tenant, statusEx.getStatus(),
            statusEx.getMessage());
        // Consider if reconnect should happen for other statuses
      }
    } else {
      log.error("Tenant: {} - Job stream onError: Non-StatusRuntimeException", tenant, t);
      // Resetting flag *only after logging*
    }
    client = null;
  }

  private void reconnectStream() {
    log.info("Tenant: {} - Attempting to reconnect job stream", tenant);
    try {
      // Reset channel first
      resetChannel();
      // Then attempt to start a new stream connection
      start();
      // Reset reconnect attempt counter on success
      reconnectAttempt = 0;
    } catch (Exception e) {
      log.error("Tenant: {} - Failed to reconnect job stream", tenant, e);
      // Schedule retry with exponential backoff
      scheduleReconnectWithBackoff();
    }
  }

  private void scheduleReconnectWithBackoff() {
    reconnectAttempt++;
    // Exponential backoff with a maximum delay
    int delaySeconds = Math.min(
        (int) Math.pow(2, reconnectAttempt),
        MAX_RECONNECT_DELAY_SECONDS);

    log.info("Tenant: {} - Scheduling reconnection attempt {} in {} seconds", tenant, reconnectAttempt, delaySeconds);

    // Schedule reconnection using a separate thread
    new Thread(() -> {
      try {
        Thread.sleep(delaySeconds * 1000);
        reconnectStream();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Tenant: {} - Reconnection thread interrupted", tenant);
      }
    }).start();
  }

  @Override
  public void onCompleted() {
    log.info("Tenant: {} - Job stream onCompleted: Server closed the stream gracefully.", tenant);
    // Resetting flag *only after logging*
    // Optionally attempt to reconnect immediately if the stream is expected to be
    // persistent
    // log.info("Stream completed, attempting immediate reconnect...");
    // reconnectStream();
    client = null;
  }

}
