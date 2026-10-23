package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ad-hoc SMS and email sending. No proto types in the public API. */
public final class MessagingService {

  private final build.buf.gen.tcnapi.exile.gate.v3.MessagingServiceGrpc.MessagingServiceBlockingStub
      stub;

  MessagingService(ManagedChannel channel) {
    this.stub = build.buf.gen.tcnapi.exile.gate.v3.MessagingServiceGrpc.newBlockingStub(channel);
  }

  public record SentMessage(String messageRef) {}

  /** A campaign the org can send through right now. channel is "sms" or "email". */
  public record Campaign(long campaignSid, String name, String channel) {}

  public SentMessage sendSms(
      long campaignSid,
      String recipient,
      String body,
      String poolId,
      String recordId,
      Map<String, String> metadata) {
    var req =
        build.buf.gen.tcnapi.exile.gate.v3.SendSmsRequest.newBuilder()
            .setCampaignSid(campaignSid)
            .setRecipient(recipient)
            .setBody(body)
            .setPoolId(poolId == null ? "" : poolId)
            .setRecordId(recordId == null ? "" : recordId);
    if (metadata != null) {
      req.putAllMetadata(metadata);
    }

    return new SentMessage(stub.sendSms(req.build()).getMessageRef());
  }

  public SentMessage sendEmail(
      long campaignSid,
      String recipient,
      String subject,
      String body,
      String poolId,
      String recordId,
      Map<String, String> metadata) {
    var req =
        build.buf.gen.tcnapi.exile.gate.v3.SendEmailRequest.newBuilder()
            .setCampaignSid(campaignSid)
            .setRecipient(recipient)
            .setSubject(subject)
            .setBody(body)
            .setPoolId(poolId == null ? "" : poolId)
            .setRecordId(recordId == null ? "" : recordId);
    if (metadata != null) {
      req.putAllMetadata(metadata);
    }

    return new SentMessage(stub.sendEmail(req.build()).getMessageRef());
  }

  /** channel is "sms", "email", or null for both. */
  public List<Campaign> listCampaigns(String channel) {
    var ch =
        switch (channel == null ? "" : channel) {
          case "sms" -> build.buf.gen.tcnapi.exile.gate.v3.MessageStatus.Channel.CHANNEL_SMS;
          case "email" -> build.buf.gen.tcnapi.exile.gate.v3.MessageStatus.Channel.CHANNEL_EMAIL;
          case "" -> build.buf.gen.tcnapi.exile.gate.v3.MessageStatus.Channel.CHANNEL_UNSPECIFIED;
          default -> throw new IllegalArgumentException("channel must be sms or email");
        };
    var res =
        stub.listCampaigns(
            build.buf.gen.tcnapi.exile.gate.v3.ListCampaignsRequest.newBuilder()
                .setChannel(ch)
                .build());
    var campaigns = new ArrayList<Campaign>();
    for (var c : res.getCampaignsList()) {
      var email =
          c.getChannel() == build.buf.gen.tcnapi.exile.gate.v3.MessageStatus.Channel.CHANNEL_EMAIL;
      campaigns.add(new Campaign(c.getCampaignSid(), c.getName(), email ? "email" : "sms"));
    }
    return campaigns;
  }
}
