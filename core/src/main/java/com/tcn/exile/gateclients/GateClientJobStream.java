package com.tcn.exile.gateclients;

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
    log.debug("GateClientJobStream created");
  }

  @Override
  public void start() {
    log.debug("GateClientJobStream started with plugin {} status {}", plugin.getName(), plugin.isRunning());
    eventStream();
  }

  public boolean isRunning() {
    if (channel == null) {
      return false;
    }
    log.debug("channel {} shutdown {} terminated {} -> {}", channel, channel.isShutdown(), channel.isTerminated(), !channel.isShutdown() && !channel.isTerminated());
    return (!channel.isShutdown() || !channel.isTerminated());
  }

  public synchronized void eventStream() {
    if (!isRunning()) {
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
        plugin.scheduleExileNamedJob(job.getJobId(), job.getExileNamedJobRequest());
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
    eventStream();
  }

  @Override
  public void onCompleted() {
    log.debug("GateClientJobStream completed");
    eventStream();
  }
}
