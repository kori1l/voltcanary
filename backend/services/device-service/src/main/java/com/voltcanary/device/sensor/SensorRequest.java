package com.voltcanary.device.sensor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record SensorRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100) String type,
        @Size(max = 50) String unit,
        @Size(max = 100) String metricMapping,
        Map<String, Object> metadata) {
}
