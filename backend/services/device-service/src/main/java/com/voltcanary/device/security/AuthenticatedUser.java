package com.voltcanary.device.security;

import java.util.UUID;import com.voltcanary.device.domain.Device;


public record AuthenticatedUser(UUID id, String email, String role) {
}
