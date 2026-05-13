package com.voltcanary.device.datasource;

import jakarta.validation.constraints.NotBlank;

public record DataSourceRequest(
        @NotBlank String sourceType,
        @NotBlank String protocol,
        @NotBlank String externalId) {
}
