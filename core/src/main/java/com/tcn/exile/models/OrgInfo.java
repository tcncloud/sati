package com.tcn.exile.models;

import jakarta.validation.constraints.NotEmpty;

public record OrgInfo(@NotEmpty String orgId, String orgName) {
}
