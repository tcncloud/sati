package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.web.dto.SendEmailRequestDto;
import com.tcn.exile.web.dto.SendMessageResponseDto;
import com.tcn.exile.web.dto.SendSmsRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for the ad-hoc SMS/email send endpoints. No framework dependencies. */
public class MessagingHandler {

  private static final Logger log = LoggerFactory.getLogger(MessagingHandler.class);

  private final ExileClientManager clientManager;

  public MessagingHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public SendMessageResponseDto sendSms(SendSmsRequestDto request) {
    log.debug(
        "sendSms(campaignSid={}, poolId={}, recordId={})",
        request.campaignSid(),
        request.poolId(),
        request.recordId());

    requireCampaignAndRecipient(request.campaignSid(), request.recipient());
    requireText(request.body(), "body");

    return SendMessageResponseDto.from(
        getClient()
            .messaging()
            .sendSms(
                request.campaignSid(),
                request.recipient(),
                request.body(),
                request.poolId(),
                request.recordId(),
                request.metadata()));
  }

  public SendMessageResponseDto sendEmail(SendEmailRequestDto request) {
    log.debug(
        "sendEmail(campaignSid={}, poolId={}, recordId={})",
        request.campaignSid(),
        request.poolId(),
        request.recordId());

    requireCampaignAndRecipient(request.campaignSid(), request.recipient());
    requireText(request.subject(), "subject");
    requireText(request.body(), "body");

    return SendMessageResponseDto.from(
        getClient()
            .messaging()
            .sendEmail(
                request.campaignSid(),
                request.recipient(),
                request.subject(),
                request.body(),
                request.poolId(),
                request.recordId(),
                request.metadata()));
  }

  private static void requireCampaignAndRecipient(long campaignSid, String recipient) {
    if (campaignSid == 0) {
      throw new IllegalArgumentException("campaignSid is required and must be non-zero");
    }
    requireText(recipient, "recipient");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
