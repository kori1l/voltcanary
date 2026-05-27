package com.voltcanary.device.account;

import java.util.UUID;

public record AccountResponse(UUID id, UUID userId, String name, String status) {
}
