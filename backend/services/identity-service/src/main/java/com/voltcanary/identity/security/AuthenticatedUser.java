package com.voltcanary.identity.security;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String role) {
}
