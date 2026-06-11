package com.voltcanary.device.dto;

import java.util.UUID;

public record AccountLifecycleStatusResponse(UUID userId, String status) {
}
