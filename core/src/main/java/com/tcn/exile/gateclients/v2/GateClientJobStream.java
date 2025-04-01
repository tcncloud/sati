package com.tcn.exile.gateclients.v2;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.plugin.PluginInterface;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public.StreamJobsRequest;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;
import io.grpc.StatusRuntimeException;
import io.grpc.Status;

@Singleton
public class GateClientJobStream extends GateClientAbstract
    implements StreamObserver<tcnapi.exile.gate.v2.Public.StreamJobsResponse> {
  protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientJobStream.class);

  @Inject
  PluginInterface plugin;

  private int reconnectAttempt = 0;
  private static final int MAX_RECONNECT_DELAY_SECONDS = 60;

  private volatile boolean activeStreamExists = false;
  private final Object streamLock = new Object();

  @Override
  @Scheduled(fixedDelay = "10s")
  public void start() {
    if (isUnconfigured()) {
      log.debug("Configuration not set, skipping job stream");
      return;
    }
    if (!plugin.isRunning()) {
      log.debug("Plugin is not running (possibly due to database disconnection), skipping job stream");
      return;
    }
    
    // Check if we already have an active stream
    synchronized(streamLock) {
      if (activeStreamExists) {
        log.debug("Job stream already active, skipping stream creation");
        return;
      }
      
      log.debug("Starting job stream");
      try {
        if (!isRunning()) {
          shutdown();
          channel = this.getChannel();
        }
        var client = GateServiceGrpc.newStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .withWaitForReady();

        client.streamJobs(StreamJobsRequest.newBuilder().build(), this);
        activeStreamExists = true;
      } catch (StatusRuntimeException e) {
        if (handleStatusRuntimeException(e)) {
          log.warn("Connection unavailable in job stream, channel reset");
        } else {
          log.error("Error in job stream: {} ({})", e.getMessage(), e.getStatus().getCode());
        }
        activeStreamExists = false;
      } catch (UnconfiguredException e) {
        log.error("Error while starting job stream {}", e.getMessage());
        activeStreamExists = false;
      } catch (Exception e) {
        log.error("Unexpected error in job stream", e);
        activeStreamExists = false;
      }
    }
  }
  public boolean isRunning() {
    if (channel == null) {
      return false;
    }
    if (channel.isTerminated()) {
      return false;
    }
    return !channel.isShutdown();
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
    synchronized(streamLock) {
      activeStreamExists = false;
    }
    if (t instanceof StatusRuntimeException) {
        StatusRuntimeException statusEx = (StatusRuntimeException) t;
        if (statusEx.getStatus().getCode() == Status.Code.CANCELLED) {
            log.warn("Stream cancelled, attempting to reconnect: {}", statusEx.getMessage());
            // Add a delay to prevent immediate reconnection flooding
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Implement reconnection logic here
            reconnectStream();
        } else {
            log.error("Stream error: {}: {}", statusEx.getStatus(), statusEx.getMessage());
        }
    } else {
        log.error("Stream error", t);
    }
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
        MAX_RECONNECT_DELAY_SECONDS
    );
    
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
    synchronized(streamLock) {
      activeStreamExists = false;
    }
  }

}
