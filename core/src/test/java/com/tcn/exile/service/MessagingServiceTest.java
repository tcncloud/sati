package com.tcn.exile.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import build.buf.gen.tcnapi.exile.gate.v3.ListCampaignsRequest;
import build.buf.gen.tcnapi.exile.gate.v3.ListCampaignsResponse;
import build.buf.gen.tcnapi.exile.gate.v3.MessageStatus;
import build.buf.gen.tcnapi.exile.gate.v3.MessagingCampaign;
import build.buf.gen.tcnapi.exile.gate.v3.MessagingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessagingServiceTest {

  private static final String SERVER_NAME = "messaging-service-test";

  private Server server;
  private ManagedChannel channel;
  private Gate gate;

  @BeforeEach
  void setUp() throws Exception {
    gate = new Gate();
    server = InProcessServerBuilder.forName(SERVER_NAME).addService(gate).build().start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME).build();
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void listCampaignsMapsChannelsToPlainStrings() {
    var campaigns = new MessagingService(channel).listCampaigns(null);

    assertEquals(MessageStatus.Channel.CHANNEL_UNSPECIFIED, gate.lastRequest.getChannel());
    assertEquals(
        List.of(
            new MessagingService.Campaign(7, "Payments", "sms"),
            new MessagingService.Campaign(8, "Receipts", "email")),
        campaigns);
  }

  @Test
  void listCampaignsPassesTheChannelFilter() {
    new MessagingService(channel).listCampaigns("email");

    assertEquals(MessageStatus.Channel.CHANNEL_EMAIL, gate.lastRequest.getChannel());
  }

  @Test
  void listCampaignsRejectsAnUnknownChannel() {
    assertThrows(
        IllegalArgumentException.class, () -> new MessagingService(channel).listCampaigns("fax"));
  }

  static class Gate extends MessagingServiceGrpc.MessagingServiceImplBase {
    volatile ListCampaignsRequest lastRequest;

    @Override
    public void listCampaigns(
        ListCampaignsRequest request, StreamObserver<ListCampaignsResponse> responseObserver) {
      lastRequest = request;
      responseObserver.onNext(
          ListCampaignsResponse.newBuilder()
              .addCampaigns(
                  MessagingCampaign.newBuilder()
                      .setCampaignSid(7)
                      .setName("Payments")
                      .setChannel(MessageStatus.Channel.CHANNEL_SMS))
              .addCampaigns(
                  MessagingCampaign.newBuilder()
                      .setCampaignSid(8)
                      .setName("Receipts")
                      .setChannel(MessageStatus.Channel.CHANNEL_EMAIL))
              .build());
      responseObserver.onCompleted();
    }
  }
}
