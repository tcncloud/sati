package com.tcn.exile.gateclients;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v1.ExileGateServiceGrpc;
import tcnapi.exile.gate.v1.Service;

@Singleton
public class GateClientResponseStream extends GateClientAbstract implements StreamObserver<Service.ResultsStreamResponse> {
  private static final Logger log = LoggerFactory.getLogger(GateClientResponseStream.class);
  private ManagedChannel channel;
  private StreamObserver<Service.ResultsStreamRequest> streamObserver;


  public boolean isRunning() {
    if (channel == null) {
      return false;
    }
    log.debug("channel {} shutdown {} terminated {} -> {}", channel, channel.isShutdown(), channel.isTerminated(), !channel.isShutdown() && !channel.isTerminated());
    return (!channel.isShutdown() && !channel.isTerminated());
  }

  @Override
  public void start() {
    responseStream();
  }

  @Override
  public void onNext(Service.ResultsStreamResponse response) {
    log.debug("GateClientResponseStream onNext {}", response);
  }

  @Override
  public void onError(Throwable throwable) {
    log.debug("GateClientResponseStream onError {}", throwable);
    responseStream();
  }

  @Override
  public void onCompleted() {
    log.debug("GateClientResponseStream onCompleted");
    responseStream();
  }

  public void sendError(Service.ResultsStreamRequest datasourceIsClosed) {
    log.debug("GateClientResponseStream sendError {}", datasourceIsClosed);
    streamObserver.onNext(datasourceIsClosed);

  }

  public void sendResult(Service.ResultsStreamRequest build) {
    if (!isRunning()) {
      log.debug("stream observer not running");
    }
    log.debug("GateClientResponseStream sendResult {}", build);
    if (streamObserver == null) {
      log.debug("streamObserver not set");
    }
    streamObserver.onNext(build);
    log.debug("After GateClientResponseStream sendResult {}", build);
  }

  public synchronized void responseStream() {
    if (!isRunning()) {
      log.debug("setting up responseStream");
      try {
        channel = getChannel();
      } catch (UnconfiguredException e) {
        throw new RuntimeException(e);
      }
      var client = ExileGateServiceGrpc.newStub(channel).withWaitForReady();
      this.streamObserver = client.resultsStream(this);
    }
  }

  public void sendEOF(String jobId) {
    log.debug("Sending EOF of {}", jobId);
    streamObserver.onNext(Service.ResultsStreamRequest.newBuilder()
        .setPayload("EOF")
        .setJobId(jobId)
        .build());

  }
}
