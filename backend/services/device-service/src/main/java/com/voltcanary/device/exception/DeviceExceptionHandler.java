package com.voltcanary.device.exception;

import com.voltcanary.device.metrics.DeviceOperationalMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@RequiredArgsConstructor
public class DeviceExceptionHandler {

    private final DeviceOperationalMetrics metrics;

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationFailedException exception) {
        metrics.operationRejected("authentication_failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiErrorResponse("authentication_failed", exception.getMessage()));
    }

    @ExceptionHandler(AccountInactiveException.class)
    ResponseEntity<ApiErrorResponse> handleAccountInactive(AccountInactiveException exception) {
        metrics.operationRejected("account_inactive");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse("account_inactive", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException exception) {
        metrics.operationRejected("not_found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("not_found", exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleConcurrentChange(org.springframework.dao.OptimisticLockingFailureException exception) {
        metrics.operationRejected("concurrent_change");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse("concurrent_change", "Record changed. Reload it before retrying."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        metrics.operationRejected("bad_request");
        return ResponseEntity.badRequest().body(new ApiErrorResponse("bad_request", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        metrics.operationRejected("validation_failed");
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("request validation failed");

        return ResponseEntity.badRequest().body(new ApiErrorResponse("validation_failed", message));
    }
}
