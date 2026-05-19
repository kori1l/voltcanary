package com.voltcanary.device.datasource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DataSourceRequest(
        @NotNull DataSourceType sourceType,
        @NotNull DataSourceProtocol protocol,
        @NotBlank String externalId) {
}
