package com.tcn.exile.web.dto;

import java.time.Instant;

public record ApiErrorResponse(int status, String error, String message, String timestamp) {

  public static ApiErrorResponse of(int status, String error, String message) {
    return new ApiErrorResponse(status, error, message, Instant.now().toString());
  }
}
