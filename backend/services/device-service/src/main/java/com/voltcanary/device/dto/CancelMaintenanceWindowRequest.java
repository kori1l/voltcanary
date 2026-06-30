package com.voltcanary.device.dto;

import jakarta.validation.constraints.Size;

public record CancelMaintenanceWindowRequest(
        @Size(max = 1000) String reason) {
}
