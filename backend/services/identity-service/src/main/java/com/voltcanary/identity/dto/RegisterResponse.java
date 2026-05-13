package com.voltcanary.identity.dto;

import java.util.UUID;

public record RegisterResponse(UUID userId, String email) {
}
