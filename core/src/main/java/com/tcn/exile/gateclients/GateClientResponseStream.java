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

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v1.Entities;
import tcnapi.exile.gate.v1.ExileGateServiceGrpc;
import tcnapi.exile.gate.v1.Service;

@Singleton
public class GateClientResponseStream extends GateClientAbstract
    implements StreamObserver<Service.ResultsStreamResponse> {
  private static final Logger log = LoggerFactory.getLogger(GateClientResponseStream.class);
  private ManagedChannel channel;
  private StreamObserver<Service.ResultsStreamRequest> streamObserver;

  public GateClientResponseStream() {
    log.debug("GateClientResponseStream created");
  }

  public GateClientResponseStream(ManagedChannel channel2) {
    log.debug("GateClientResponseStream created with channel {}", channel2);
    this.channel = channel2;
    start();
  }

  public boolean isRunning() {
    if ((channel == null) || (streamObserver == null)) {
      return false;
    }
    if (channel.isShutdown()) return false;
    if (channel.isTerminated()) return false;
    return true;
//    log.debug("channel {} shutdown {} terminated {} -> return {}", channel, channel.isShutdown(),
//        channel.isTerminated(),
//        !channel.isShutdown() && !channel.isTerminated() && (streamObserver != null));
//    return (!channel.isShutdown() && !channel.isTerminated()) && (streamObserver != null);
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
    if (channel != null) this.shutdown();
    responseStream();
  }

  @Override
  public void onCompleted() {
    log.debug("GateClientResponseStream onCompleted");
    if (channel != null) this.shutdown();
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
      log.debug("setting up responseStream with a new channel");
//            if (channel == null) {

      try {
        channel = getChannel();
      } catch (UnconfiguredException e) {
        throw new RuntimeException(e);
      }
//        }
      var client = ExileGateServiceGrpc.newStub(channel).withWaitForReady();
      log.debug("setting stream observer ");
      this.streamObserver = client.resultsStream(this);
      log.debug("stream observer set");
    } else {
      log.debug("response stream, already running");
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
