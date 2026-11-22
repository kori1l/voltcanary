package com.voltcanary.device.dto;

import java.util.UUID;

public record AccountResponse(UUID id, UUID userId, String name, String status) {}
