package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.Size;

public record UpdateModelProviderRequest(
        @Size(max = 120) String name,
        @Size(max = 512) String baseUrl,
        String status) {
}
