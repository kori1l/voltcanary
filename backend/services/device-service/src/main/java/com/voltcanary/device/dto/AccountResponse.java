package com.voltcanary.device.dto;

import java.util.UUID;import com.voltcanary.device.domain.Device;


public record AccountResponse(UUID id, UUID userId, String name, String status) {
}
