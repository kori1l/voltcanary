package com.voltcanary.common.web;

import java.time.Instant;
import java.util.List;

public record ValidationErrorResponse(
        ErrorCode code,
        String message,
        List<FieldError> errors,
        String path,
        String correlationId,
        Instant timestamp) {

    public record FieldError(String field, String message) {
    }

    public static ValidationErrorResponse of(
            List<FieldError> errors,
            String path,
            String correlationId) {
        return new ValidationErrorResponse(
                ErrorCode.VALIDATION_ERROR,
                "Request validation failed",
                errors,
                path,
                correlationId,
                Instant.now());
    }
}
