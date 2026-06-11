package com.voltcanary.identity.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityExceptionHandler.class);

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiErrorResponse> handleDuplicateEmail(DuplicateEmailException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("duplicate_email", "Email is already registered", request));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ApiErrorResponse> handleAuthenticationFailed(AuthenticationFailedException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("authentication_failed", exception.getMessage(), request));
    }

    @ExceptionHandler(AuthorizationFailedException.class)
    ResponseEntity<ApiErrorResponse> handleAuthorizationFailed(AuthorizationFailedException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(error("authorization_failed", exception.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("request validation failed");

        return ResponseEntity
                .badRequest()
                .body(error("validation_failed", message, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ResponseEntity
                .badRequest()
                .body(error("bad_request", "Request body is invalid", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(error("method_not_allowed", "HTTP method is not supported for this path", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("not_found", "Resource not found", request));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(org.springframework.web.server.ResponseStatusException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(error("downstream_unavailable", exception.getReason() == null ? "Request failed" : exception.getReason(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled identity-service exception", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("internal_error", "Internal server error", request));
    }

    private ApiErrorResponse error(String code, String message, HttpServletRequest request) {
        return new ApiErrorResponse(
                code,
                message,
                request.getRequestURI(),
                correlationId(request),
                java.time.Instant.now());
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? null : requestId.trim();
    }
}
