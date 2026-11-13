package com.voltcanary.aiagent.exception;

import com.voltcanary.aiagent.security.AuthenticationFailedException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiAgentExceptionHandler {

    @ExceptionHandler(com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected.class)
    ResponseEntity<Map<String, String>> handleIntentPlan(com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.getMessage(), "message", exception.getMessage()));
    }

    @ExceptionHandler(ModelPlanningFailedException.class)
    ResponseEntity<Map<String, String>> handleModelPlanning(ModelPlanningFailedException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("code", exception.code(), "message", exception.getReason()));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<Map<String, String>> handleAuthentication(AuthenticationFailedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
