package com.tcn.exile.internal;

import com.tcn.exile.ExileConfig;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Creates mTLS gRPC channels from {@link ExileConfig}. */
public final class ChannelFactory {

  private ChannelFactory() {}

  /** Create a new mTLS channel to the gate server. Caller owns the channel lifecycle. */
  public static ManagedChannel create(ExileConfig config) {
    try {
      // Handle PKCS#1 (BEGIN RSA PRIVATE KEY) → PKCS#8 (BEGIN PRIVATE KEY) conversion.
      var keyPem = config.privateKey();
      if (keyPem.contains("BEGIN RSA PRIVATE KEY")) {
        keyPem = convertPkcs1ToPkcs8Pem(keyPem);
      }

      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(
                  new ByteArrayInputStream(config.rootCert().getBytes(StandardCharsets.UTF_8)))
              .keyManager(
                  new ByteArrayInputStream(config.publicCert().getBytes(StandardCharsets.UTF_8)),
                  new ByteArrayInputStream(keyPem.getBytes(StandardCharsets.UTF_8)))
              .build();

      // Use InetSocketAddress to avoid the unix domain socket name resolver on macOS.
      return NettyChannelBuilder.forAddress(
              new InetSocketAddress(config.apiHostname(), config.apiPort()))
          .sslContext(sslContext)
          .keepAliveTime(32, TimeUnit.SECONDS)
          .keepAliveTimeout(30, TimeUnit.SECONDS)
          .keepAliveWithoutCalls(true)
          .idleTimeout(30, TimeUnit.MINUTES)
          .flowControlWindow(4 * 1024 * 1024) // 4MB — match envoy upstream window
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

  /**
   * Converts a PKCS#1 RSA private key PEM to PKCS#8 PEM format. Netty's SSL only accepts PKCS#8
   * (BEGIN PRIVATE KEY), but exile certificates use PKCS#1 (BEGIN RSA PRIVATE KEY).
   */
  private static String convertPkcs1ToPkcs8Pem(String pkcs1Pem) throws Exception {
    var base64 =
        pkcs1Pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    var pkcs1Bytes = Base64.getDecoder().decode(base64);

    var keyFactory = KeyFactory.getInstance("RSA");
    var rsaKey = org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(pkcs1Bytes);
    var spec =
        new RSAPrivateCrtKeySpec(
            rsaKey.getModulus(),
            rsaKey.getPublicExponent(),
            rsaKey.getPrivateExponent(),
            rsaKey.getPrime1(),
            rsaKey.getPrime2(),
            rsaKey.getExponent1(),
            rsaKey.getExponent2(),
            rsaKey.getCoefficient());
    PrivateKey privateKey = keyFactory.generatePrivate(spec);
    var pkcs8Bytes = privateKey.getEncoded();

    var pkcs8Base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8Bytes);
    return "-----BEGIN PRIVATE KEY-----\n" + pkcs8Base64 + "\n-----END PRIVATE KEY-----\n";
  }
}
