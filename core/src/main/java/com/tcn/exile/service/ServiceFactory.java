package com.tcn.exile.service;

import io.grpc.ManagedChannel;

/** Factory for creating all domain service clients. Keeps ManagedChannel out of public API. */
public final class ServiceFactory {
  private ServiceFactory() {}

  public record Services(
      AgentService agent,
      CallService call,
      RecordingService recording,
      ScrubListService scrubList,
      ConfigService config) {}

  public static Services create(ManagedChannel channel) {
    return new Services(
        new AgentService(channel),
        new CallService(channel),
        new RecordingService(channel),
        new ScrubListService(channel),
        new ConfigService(channel));
  }
}
