package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.web.dto.MessagingCampaignDto;
import com.tcn.exile.web.dto.SendEmailRequestDto;
import com.tcn.exile.web.dto.SendMessageResponseDto;
import com.tcn.exile.web.dto.SendSmsRequestDto;
import io.grpc.StatusRuntimeException;
import java.util.List;
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

    long campaignSid = parseCampaignSid(request.campaignSid());
    requireText(request.recipient(), "recipient");
    requireText(request.body(), "body");

    try {
      return SendMessageResponseDto.from(
          getClient()
              .messaging()
              .sendSms(
                  campaignSid,
                  request.recipient(),
                  request.body(),
                  request.poolId(),
                  request.recordId(),
                  request.metadata()));
    } catch (StatusRuntimeException e) {
      throw rejected(e);
    }
  }

  public SendMessageResponseDto sendEmail(SendEmailRequestDto request) {
    log.debug(
        "sendEmail(campaignSid={}, poolId={}, recordId={})",
        request.campaignSid(),
        request.poolId(),
        request.recordId());

    long campaignSid = parseCampaignSid(request.campaignSid());
    requireText(request.recipient(), "recipient");
    requireText(request.subject(), "subject");
    requireText(request.body(), "body");

    try {
      return SendMessageResponseDto.from(
          getClient()
              .messaging()
              .sendEmail(
                  campaignSid,
                  request.recipient(),
                  request.subject(),
                  request.body(),
                  request.poolId(),
                  request.recordId(),
                  request.metadata()));
    } catch (StatusRuntimeException e) {
      throw rejected(e);
    }
  }

  /** channel is "sms", "email", or blank for both. */
  public List<MessagingCampaignDto> listCampaigns(String channel) {
    log.debug("listCampaigns(channel={})", channel);

    var ch = channel == null || channel.isBlank() ? null : channel.trim().toLowerCase();
    if (ch != null && !ch.equals("sms") && !ch.equals("email")) {
      throw new IllegalArgumentException("channel must be sms or email");
    }

    try {
      return getClient().messaging().listCampaigns(ch).stream()
          .map(MessagingCampaignDto::from)
          .toList();
    } catch (StatusRuntimeException e) {
      throw rejected(e);
    }
  }

  private static RuntimeException rejected(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case INVALID_ARGUMENT, NOT_FOUND, FAILED_PRECONDITION ->
          new IllegalArgumentException(e.getStatus().getDescription(), e);
      default -> e;
    };
  }

  private static long parseCampaignSid(String campaignSid) {
    requireText(campaignSid, "campaignSid");
    long sid;
    try {
      sid = Long.parseLong(campaignSid.trim());
    } catch (NumberFormatException e) {
      sid = 0;
    }
    if (sid <= 0) {
      throw new IllegalArgumentException("campaignSid must be a positive whole number");
    }
    return sid;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
