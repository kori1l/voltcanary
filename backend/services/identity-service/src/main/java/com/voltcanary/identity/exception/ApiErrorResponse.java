package com.voltcanary.identity.exception;

import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        String path,
        String correlationId,
        Instant timestamp) {
}
