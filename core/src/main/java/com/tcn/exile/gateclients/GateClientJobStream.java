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

package com.tcn.exile.gateclients;

import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.plugin.PluginInterface;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v1.ExileGateServiceGrpc;
import tcnapi.exile.gate.v1.Service;

@Singleton
public class GateClientJobStream extends GateClientAbstract implements StreamObserver<Service.JobStreamResponse> {
  private static final Logger log = LoggerFactory.getLogger(GateClientJobStream.class);

  @Inject
  PluginInterface plugin;

  private ManagedChannel channel;
  private StreamObserver<Service.JobStreamRequest> requestObserver;

  public GateClientJobStream() {
    // throw new RuntimeException("Unimplemented");
    log.debug("GateClientJobStream created");
    // start();
  }

  public GateClientJobStream(PluginInterface plugin, ConfigEvent event) {
    this.plugin = plugin;
    this.event = event;
    log.debug("GateClientJobStream created with plugin {}", plugin.getName());
    // start();
  }

  @Override
  public void start() {
    log.debug("GateClientJobStream started with plugin {} status {}", plugin.getName(), plugin.isRunning());
    eventStream();
  }

  public boolean isRunning() {
    if (channel == null) {
//      log.debug("channel is null, JobStream is not running");
      return false;
    }
//    log.debug("channel {} shutdown {} terminated {} -> {}", channel, channel.isShutdown(), channel.isTerminated(), !channel.isShutdown() && !channel.isTerminated());
    return (!channel.isShutdown() || !channel.isTerminated());
  }

  public synchronized void eventStream() {
    if (!isRunning()) {
      log.debug("starting JobStream");
      try {
        channel = getChannel();
        var client = ExileGateServiceGrpc.newStub(channel).withWaitForReady();
        this.requestObserver = client.jobStream(this);
      } catch (UnconfiguredException ex) {
        // TODO decide what to do if we don't have a channel
        ex.printStackTrace();
      }
    }
  }

  @Override
  public void onNext(Service.JobStreamResponse job) {
    log.info("job {} type {}", job.getJobId(), job.getJobCase().name());

    try {
      if (job.hasInfo()) {
        plugin.scheduleInfo(job.getJobId(), job.getInfo());
      } else if (job.hasExileAgentCall()) {
        plugin.scheduleExileAgentCall(job.getJobId(), job.getExileAgentCall());
      } else if (job.hasExileTelephonyResult()) {
        plugin.scheduleExileTelephonyResult(job.getJobId(), job.getExileTelephonyResult());
      } else if (job.hasExileAgentResponse()) {
        plugin.scheduleExileAgentRespose(job.getJobId(), job.getExileAgentResponse());
      } else if (job.hasExileNamedJobRequest()) {
        if (job.getExileNamedJobRequest().hasListPools()) {
          plugin.listPools(job.getJobId());
        } else if (job.getExileNamedJobRequest().hasGetPoolStatus()) {
          plugin.getPoolStatus(job.getJobId(), job.getExileNamedJobRequest().getGetPoolStatus().getPoolId());
        } else if (job.getExileNamedJobRequest().hasGetPoolRecords()) {
          plugin.getPoolRecords(job.getJobId(), job.getExileNamedJobRequest().getGetPoolRecords().getPoolId());
        } else {
          plugin.scheduleExileNamedJob(job.getJobId(), job.getExileNamedJobRequest());
        }
      } else {
        // TODO report back an error & reject job
      }

      requestObserver.onNext(Service.JobStreamRequest.newBuilder()
          .setJobId(job.getJobId())
          .build());
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  @Override
  public void onError(Throwable throwable) {
    log.debug("GateClientJobStream error {}", throwable.getMessage());
    channel.shutdownNow();
//    if (isRunning()) {
//      log.debug("shutting down channel");
//      channel.shutdownNow();
//    }
//    eventStream();
  }

  @Override
  public void onCompleted() {
    log.debug("GateClientJobStream completed");
    channel.shutdownNow();
//    eventStream();
  }
}
