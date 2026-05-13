package com.voltcanary.device.security;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String role) {
}
