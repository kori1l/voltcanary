package com.voltcanary.device.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record DeviceRequest(
        UUID locationId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank String type,
        @Size(max = 255) String manufacturer,
        @Size(max = 255) String model,
        Map<String, Object> metadata) {
}
