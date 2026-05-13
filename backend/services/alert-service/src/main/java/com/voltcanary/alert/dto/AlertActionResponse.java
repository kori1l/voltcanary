package com.voltcanary.alert.dto;

import java.util.UUID;

public record AlertActionResponse(
    String status,
    UUID alertId
) {
}
