package com.voltcanary.common.web;

import java.time.Instant;

public record ApiError(
        ErrorCode code,
        String message,
        String path,
        String correlationId,
        Instant timestamp) {

    public static ApiError of(ErrorCode code, String message, String path, String correlationId) {
        return new ApiError(code, message, path, correlationId, Instant.now());
    }
}
