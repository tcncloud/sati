package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import java.util.Map;

/** Ad-hoc SMS and email sending. No proto types in the public API. */
public final class MessagingService {

  private final build.buf.gen.tcnapi.exile.gate.v3.MessagingServiceGrpc.MessagingServiceBlockingStub
      stub;

  MessagingService(ManagedChannel channel) {
    this.stub = build.buf.gen.tcnapi.exile.gate.v3.MessagingServiceGrpc.newBlockingStub(channel);
  }

  /**
   * The campaign pins the sender, so there is no from-address. messageRef correlates the send with
   * its pool/record; it is not a handle on the sent message.
   */
  public record SentMessage(String messageRef) {}

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
}
