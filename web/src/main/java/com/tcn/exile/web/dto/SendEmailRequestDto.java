package com.tcn.exile.web.dto;

import java.util.Map;

public record SendEmailRequestDto(
    String campaignSid,
    String recipient,
    String subject,
    String body,
    String poolId,
    String recordId,
    Map<String, String> metadata) {}
