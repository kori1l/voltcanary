package com.voltcanary.device.device;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record DeviceRequest(
        UUID locationId,
        @NotBlank String name,
        @NotBlank String type,
        String manufacturer,
        String model) {
}
