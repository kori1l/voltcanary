package com.voltcanary.device.dto;

import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.DataSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;


public record DataSourceRequest(
        @NotNull DataSourceType sourceType,
        @NotNull DataSourceProtocol protocol,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String externalId,
        Map<String, Object> mapping,
        Map<String, Object> adapterConfig,
        Map<String, String> credentialReferences,
        @Size(max = 64) String homeRegion) {

    public DataSourceRequest(DataSourceType sourceType, DataSourceProtocol protocol, String externalId) {
        this(sourceType, protocol, externalId, Map.of(), Map.of(), Map.of(), null);
    }
}
