package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

/**
 * Shared DTO for simple success/failure responses.
 * Replaces Map.of("success", true) throughout the services.
 */
@OpenApiByFields
public class SuccessResult {
    public boolean success;

    public SuccessResult() {
        this.success = true;
    }

    public SuccessResult(boolean success) {
        this.success = success;
    }
}
