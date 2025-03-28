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

@Singleton
public class GateClientJobStream extends GateClientAbstract
    implements StreamObserver<tcnapi.exile.gate.v2.Public.StreamJobsResponse> {
  protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GateClientJobStream.class);

  @Inject
  PluginInterface plugin;

  @Override
  @Scheduled(fixedDelay = "10s")
  public void start() {
    if (isUnconfigured()) {
      log.trace("The configuration was not set, we will not start the job stream");
      return;
    }
    if (!plugin.isRunning()) {
      log.trace("The plugin is not running, we will not start the job stream");
      return;
    }
    log.debug("start()");
    try {
      if (!isRunning()) {
        shutdown();
        channel = this.getChannel();
      }
      var client = GateServiceGrpc.newStub(channel)
          // multiple of 10 seconds so the deatline will be triggered in the next iteration
          // of the scheduled start() method
          .withDeadlineAfter(30, TimeUnit.SECONDS)
          .withWaitForReady();

      client.streamJobs(StreamJobsRequest.newBuilder().build(), this);
    } catch (
        UnconfiguredException e) {
      log.error("Error while starting job stream {}", e.getMessage());
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
    Context.current().withCancellation().cancel(t);
  }

  @Override
  public void onCompleted() {
  }

}
