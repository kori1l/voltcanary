package com.voltcanary.alert.controller;

import com.voltcanary.vcl.VclException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
        ConfigurableRuleReservationsController.class,
        AutomationGrantController.class,
        EvidencePolicyController.class,
        VclWorkerGrantController.class,
        RuleCutoverReviewsController.class,
        RuleWindowAlertEffectsController.class,
        RulePauseReviewsController.class,
    }
)
public class ConfigurableRuleErrors {

    public record Error(
        String code,
        String stage,
        String path,
        boolean retryable,
        String correlationId,
        Map<String, Object> details
    ) {}

    @ExceptionHandler(VclException.class)
    public ResponseEntity<Error> handle(VclException failure) {
        HttpStatus status = switch (failure.code()) {
            case "ACCOUNT_ACCESS_REVOKED" -> HttpStatus.FORBIDDEN;
            case "ALERT_EFFECT_RECEIPT_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            case
                "REVIEW_UNAVAILABLE",
                "GRANT_UNAVAILABLE",
                "SOURCE_BINDING_UNAVAILABLE",
                "DATASET_UNAVAILABLE",
                "GRANT_FIELD_UNAVAILABLE",
                "EVIDENCE_POLICY_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            case
                "IDEMPOTENCY_KEY_REUSED",
                "REVIEW_CONFLICT",
                "REVIEW_EXPIRED",
                "REVIEW_STATE_CONFLICT",
                "GRANT_SCOPE_CHANGED",
                "GRANT_EPOCH_CONFLICT",
                "GRANT_CHANGED",
                "EVIDENCE_POLICY_INACTIVE" -> HttpStatus.CONFLICT;
            case "GRANT_EXPIRY_INVALID" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "REVIEW_QUOTA", "EVIDENCE_POLICY_QUOTA" -> HttpStatus.TOO_MANY_REQUESTS;
            case
                "AUTOMATION_GRANT_INACTIVE",
                "ALERT_PROPOSAL_CONFLICT",
                "ALERT_EVIDENCE_CHANGED" -> HttpStatus.CONFLICT;
            case
                "RULE_STORAGE_UNAVAILABLE",
                "ACCOUNT_OWNER_UNAVAILABLE",
                "GRANT_STORAGE_UNAVAILABLE",
                "GRANT_STATE_CORRUPT",
                "OWNER_SNAPSHOT_INVALID",
                "OWNER_SNAPSHOT_EXPIRED",
                "SOURCE_BINDING_OWNER_UNAVAILABLE",
                "SOURCE_CAPTURE_OWNER_UNAVAILABLE",
                "DATASET_OWNER_UNAVAILABLE",
                "EVIDENCE_POLICY_STATE_CORRUPT" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "OWNER_ACCESS_REVOKED", "OWNER_AUTHENTICATION_REQUIRED" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(
            new Error(
                failure.code(),
                "RULE_OWNER",
                failure.path(),
                status == HttpStatus.SERVICE_UNAVAILABLE,
                null,
                Map.of()
            )
        );
    }
}
