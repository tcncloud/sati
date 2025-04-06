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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.StreamJobsRequest;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;

@Singleton
@Requires(property = "sati.tenant.type", value = "never")
public class GateClientJobStream extends GateClientAbstract
    implements StreamObserver<tcnapi.exile.gate.v2.Public.StreamJobsResponse> {
  protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientJobStream.class);

  @Inject
  PluginInterface plugin;

  private int reconnectAttempt = 0;
  private static final int MAX_RECONNECT_DELAY_SECONDS = 60;

  private final Lock streamLock = new ReentrantLock();

  private GateServiceGrpc.GateServiceStub client;

  @Override
  @Scheduled(fixedDelay = "1s")
  public void start() {
    if (streamLock.tryLock()) {
      if (isUnconfigured()) {
        log.debug("Configuration not set, skipping job stream");
        streamLock.unlock();
        return;
      }
      if (!plugin.isRunning()) {
        log.debug("Plugin is not running (possibly due to database disconnection), skipping job stream");
        streamLock.unlock();
        return;
      }
      if (isRunning()) {
        log.trace("Job stream already running, skipping start");
        streamLock.unlock();
        return;
      }

      // Check if we already have an active stream
      // Log immediately after acquiring the lock
      log.debug("Thread {} acquired streamLock.", Thread.currentThread().getName());
      try {
        log.debug("Attempting to start job stream");
        if (!isRunning()) {
          shutdown(); // shutdown() acts on static channel
        }
        // Use getChannel() directly here
        this.client = GateServiceGrpc.newStub(getChannel())
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .withWaitForReady();
        this.client.streamJobs(StreamJobsRequest.newBuilder().build(), this);
      } catch (StatusRuntimeException e) {
        if (handleStatusRuntimeException(e)) {
          log.warn("Connection unavailable in job stream, channel reset");
        } else {
          log.error("Error in job stream: {} ({})", e.getMessage(), e.getStatus().getCode());
        }
      } catch (UnconfiguredException e) {
        log.error("Error while starting job stream {}", e.getMessage());
      } catch (Exception e) {
        log.error("Unexpected error in job stream", e);
      } finally {
        streamLock.unlock();
      }
    } else {
      log.debug("streamLock already locked, skipping start");
    }
  }

  public boolean isRunning() {
    return client != null ;
  }

  @Override
  public void onNext(StreamJobsResponse value) {
    log.debug("Received {} job", value.getJobId());
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
        log.error("Unknown job type {}", value.getUnknownFields());
      }

    } catch (UnconfiguredException e) {
      log.error("Error while handling job {}", value.getJobId());
    }
  }

  @Override
  public void onError(Throwable t) {
    // Log the error *before* changing the state
    if (t instanceof StatusRuntimeException) {
      StatusRuntimeException statusEx = (StatusRuntimeException) t;
      log.error("Job stream onError: Status={}, Message={}", statusEx.getStatus(), statusEx.getMessage(), statusEx);
      // Resetting flag *only after logging*
      if (statusEx.getStatus().getCode() == Status.Code.CANCELLED) {
        log.warn("Stream cancelled by server or network issue, attempting reconnect...");
        // Add a delay to prevent immediate reconnection flooding
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        reconnectStream(); // Attempt reconnect
      } else if (statusEx.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        log.warn("Stream unavailable, likely network issue or server restart. Attempting reconnect...");
        // Add a delay
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        reconnectStream(); // Attempt reconnect (resetChannel is called within)
      } else {
        log.error("Unhandled StatusRuntimeException in job stream. Status: {}, Message: {}", statusEx.getStatus(),
            statusEx.getMessage());
        // Consider if reconnect should happen for other statuses
      }
    } else {
      log.error("Job stream onError: Non-StatusRuntimeException", t);
      // Resetting flag *only after logging*
    }
    client = null;
  }

  private void reconnectStream() {
    log.info("Attempting to reconnect job stream");
    try {
      // Reset channel first
      resetChannel();
      // Then attempt to start a new stream connection
      start();
      // Reset reconnect attempt counter on success
      reconnectAttempt = 0;
    } catch (Exception e) {
      log.error("Failed to reconnect job stream", e);
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

    log.info("Scheduling reconnection attempt {} in {} seconds", reconnectAttempt, delaySeconds);

    // Schedule reconnection using a separate thread
    new Thread(() -> {
      try {
        Thread.sleep(delaySeconds * 1000);
        reconnectStream();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Reconnection thread interrupted");
      }
    }).start();
  }

  @Override
  public void onCompleted() {
    log.info("Job stream onCompleted: Server closed the stream gracefully.");
    // Resetting flag *only after logging*
    // Optionally attempt to reconnect immediately if the stream is expected to be
    // persistent
    // log.info("Stream completed, attempting immediate reconnect...");
    // reconnectStream();
    client = null;
  }

}
