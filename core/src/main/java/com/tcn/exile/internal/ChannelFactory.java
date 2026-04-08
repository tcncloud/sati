package com.tcn.exile.internal;

import com.tcn.exile.ExileConfig;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Creates mTLS gRPC channels from {@link ExileConfig}. */
public final class ChannelFactory {

  private ChannelFactory() {}

  /** Create a new mTLS channel to the gate server. Caller owns the channel lifecycle. */
  public static ManagedChannel create(ExileConfig config) {
    try {
      SslContext sslContext =
          GrpcSslContexts.forClient()
              .trustManager(
                  new ByteArrayInputStream(config.rootCert().getBytes(StandardCharsets.UTF_8)))
              .keyManager(
                  new ByteArrayInputStream(config.publicCert().getBytes(StandardCharsets.UTF_8)),
                  new ByteArrayInputStream(config.privateKey().getBytes(StandardCharsets.UTF_8)))
              .build();

      return NettyChannelBuilder.forAddress(config.apiHostname(), config.apiPort())
          .overrideAuthority("exile-proxy")
          .sslContext(sslContext)
          .keepAliveTime(32, TimeUnit.SECONDS)
          .keepAliveTimeout(30, TimeUnit.SECONDS)
          .keepAliveWithoutCalls(true)
          .idleTimeout(30, TimeUnit.MINUTES)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create gRPC channel", e);
    }
  }

  /** Gracefully shut down a channel, forcing after timeout. */
  public static void shutdown(ManagedChannel channel) {
    if (channel == null) return;
    channel.shutdown();
    try {
      if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      channel.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
