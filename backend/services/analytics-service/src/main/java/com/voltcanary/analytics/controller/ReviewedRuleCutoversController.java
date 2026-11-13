package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.vcl.adapter.JdbcRuleDeployments;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshots;
import com.voltcanary.vcl.VclException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics/configurable/rule-cutovers")
public class ReviewedRuleCutoversController {
    private final ReviewedRuleCutovers cutovers;

    public ReviewedRuleCutoversController(ReviewedRuleCutovers cutovers) {
        this.cutovers = cutovers;
    }

    @PostMapping("/checkpoint")
    public RuleCompilationSnapshots.StartCheckpoint checkpoint(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.Preview preview) {
        return cutovers.preview(authorization, actor, preview);
    }

    @PostMapping("/window-comparison")
    public ReviewedRuleCutovers.WindowComparison compareWindows(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.WindowComparisonRequest request) {
        return cutovers.compareWindows(authorization, actor, request);
    }

    @PostMapping("/historical-window-comparison")
    public ReviewedRuleCutovers.HistoricalWindowComparison compareHistoricalWindow(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization,
            @RequestBody ReviewedRuleCutovers.HistoricalWindowComparisonRequest request) {
        return cutovers.compareHistoricalWindow(authorization, actor, request);
    }

    @PostMapping("/historical-window-corrections")
    public ReviewedRuleCutovers.WindowCorrection publishHistoricalWindow(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.WindowCorrectionRequest request) {
        return cutovers.publishHistoricalWindow(authorization, actor, request);
    }

    @PostMapping("/{reviewId}/apply")
    public ResponseEntity<JdbcRuleDeployments.Deployment> apply(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID reviewId,
            @RequestBody RuleCompilationSnapshots.StartCheckpoint checkpoint) {
        var deployment = cutovers.apply(authorization, actor, reviewId, checkpoint);
        if (deployment.state() == JdbcRuleDeployments.State.ACTIVATING) {
            return ResponseEntity.accepted().header("Retry-After", "2").body(deployment);
        }
        return ResponseEntity.ok(deployment);
    }

    @PostMapping("/pause/{reviewId}/apply")
    public JdbcRuleDeployments.Deployment pause(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID reviewId) {
        return cutovers.pause(authorization, actor, reviewId);
    }

    @ExceptionHandler(VclException.class)
    public ResponseEntity<Map<String, String>> failure(VclException failure) {
        var code = failure.code();
        HttpStatus status = switch (code) {
            case "OWNER_ACCESS_REVOKED", "OWNER_AUTHENTICATION_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "RULE_CUTOVER_REVIEW_UNAVAILABLE", "RULE_PAUSE_REVIEW_UNAVAILABLE",
                    "DEPLOYMENT_BUNDLE_UNAVAILABLE", "DEPLOYMENT_UNAVAILABLE", "WINDOW_MEASURE_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            case "RULE_CUTOVER_REVIEW_OWNER_UNAVAILABLE", "RULE_PAUSE_REVIEW_OWNER_UNAVAILABLE",
                    "DEPLOYMENT_STORAGE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
