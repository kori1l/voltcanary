package com.voltcanary.identity.dto;

import java.util.UUID;

public record MeResponse(UUID id, String email, String role) {
}
