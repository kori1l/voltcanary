package com.voltcanary.device.datasource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DataSourceRequest(
        @NotNull DataSourceType sourceType,
        @NotNull DataSourceProtocol protocol,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String externalId) {
}
