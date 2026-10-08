package com.voltcanary.dashboard.controller;

import com.voltcanary.dashboard.domain.DashboardFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class DashboardErrorHandler {
    public record FieldError(String field, String message) {
    }

    public record ErrorResponse(String code, String message, List<FieldError> errors,
            String path, String correlationId, Instant timestamp) {
    }

    @ExceptionHandler(DashboardFailure.class)
    public ResponseEntity<ErrorResponse> domain(DashboardFailure failure, HttpServletRequest request) {
        int status = switch (failure.code()) {
            case "NOT_FOUND" -> 404;
            case "UNAUTHENTICATED" -> 401;
            case "ACTOR_INACTIVE", "NO_ACCESS" -> 403;
            case "OWNERSHIP_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", "READ_TIMEOUT", "INVALID_READ_RESPONSE" -> 503;
            case "CONFLICT" -> 409;
            case "PRECONDITION_REQUIRED" -> 428;
            case "QUOTA_EXCEEDED", "VALIDATION_ERROR", "LIMIT_EXCEEDED" -> 422;
            default -> 500;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(failure.code(), failure.getMessage(),
                failure.field() == null ? List.of() : List.of(new FieldError(failure.field(), failure.getMessage())),
                request.getRequestURI(), request.getHeader("X-Correlation-Id"), Instant.now()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception ignored, HttpServletRequest request) {
        return domain(new DashboardFailure("VALIDATION_ERROR", "Request contains invalid typed values", "request"), request);
    }
}
