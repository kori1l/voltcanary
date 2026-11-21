package com.voltcanary.analytics.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.vcl.adapter.JdbcRuleDeployments;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshots;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class ReviewedRuleCutoversController {
    private static final ObjectMapper APPLY_JSON = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final VclParser PARSER = new VclParser();
    private final ReviewedRuleCutovers cutovers;

    @GetMapping("/configurable-rules/circles/{id}/dependencies")
    public JsonNode circleDependencies(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id, @RequestParam long revision) {
        return cutovers.circleDependencies(authorization, actor, id, revision);
    }

    @PostMapping("/configurable-rules/compilations")
    public ReviewedRuleCutovers.CompilationReceipt compile(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody byte[] body) {
        return cutovers.compile(authorization, actor, body);
    }

    @PostMapping("/configurable/rule-cutovers/checkpoint")
    public RuleCompilationSnapshots.StartCheckpoint checkpoint(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.Preview preview) {
        return cutovers.preview(authorization, actor, preview);
    }

    @PostMapping("/configurable/rule-cutovers/window-comparison")
    public ReviewedRuleCutovers.WindowComparison compareWindows(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.WindowComparisonRequest request) {
        return cutovers.compareWindows(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/session-preview")
    public ReviewedRuleCutovers.SessionPreview previewSession(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.SessionPreviewRequest request) {
        return cutovers.previewSession(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/window-preview")
    public ReviewedRuleCutovers.WindowPreview previewWindow(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.WindowPreviewRequest request) {
        return cutovers.previewWindow(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/historical-window-comparison")
    public ReviewedRuleCutovers.HistoricalWindowComparison compareHistoricalWindow(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization,
            @RequestBody ReviewedRuleCutovers.HistoricalWindowComparisonRequest request) {
        return cutovers.compareHistoricalWindow(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/historical-measure")
    public ReviewedRuleCutovers.HistoricalMeasure historicalMeasure(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.HistoricalMeasureRequest request) {
        return cutovers.readHistoricalMeasure(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/historical-measure-list")
    public ReviewedRuleCutovers.HistoricalMeasurePage historicalMeasures(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.HistoricalMeasurePageRequest request) {
        return cutovers.listHistoricalMeasures(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/deployed-checkpoint")
    public ReviewedRuleCutovers.DeployedRuleCheckpoint deployedCheckpoint(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody ReviewedRuleCutovers.DeployedRuleRequest request) {
        return cutovers.deployedRuleCheckpoint(authorization, actor, request);
    }

    @PostMapping("/configurable/rule-cutovers/historical-window-corrections")
    public ReviewedRuleCutovers.WindowCorrection publishHistoricalWindow(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody byte[] body) {
        return cutovers.publishHistoricalWindow(authorization, actor,
                applyBody(body, ReviewedRuleCutovers.WindowCorrectionRequest.class));
    }

    @PostMapping("/configurable/rule-cutovers/{reviewId}/apply")
    public ResponseEntity<JdbcRuleDeployments.Deployment> apply(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID reviewId,
            @RequestBody byte[] body) {
        var deployment = cutovers.apply(authorization, actor, reviewId,
                applyBody(body, RuleCompilationSnapshots.StartCheckpoint.class));
        if (deployment.state() == JdbcRuleDeployments.State.ACTIVATING) {
            return ResponseEntity.accepted().header("Retry-After", "2").body(deployment);
        }
        return ResponseEntity.ok(deployment);
    }

    @PostMapping("/configurable/rule-cutovers/pause/{reviewId}/apply")
    public JdbcRuleDeployments.Deployment pause(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID reviewId) {
        return cutovers.pause(authorization, actor, reviewId);
    }

    private static <T> T applyBody(byte[] body, Class<T> type) {
        try {
            return APPLY_JSON.treeToValue(PARSER.readJson(body), type);
        } catch (JsonProcessingException | VclException invalid) {
            throw new VclException("RULE_APPLY_REQUEST_INVALID", "");
        }
    }

    @ExceptionHandler(VclException.class)
    public ResponseEntity<Map<String, String>> failure(VclException failure) {
        var code = failure.code();
        HttpStatus status = switch (code) {
            case "RULE_COMPILATION_REQUEST_INVALID", "RULE_APPLY_REQUEST_INVALID", "WINDOW_MEASURE_REQUEST_INVALID" -> HttpStatus.BAD_REQUEST;
            case "OWNER_ACCESS_REVOKED", "OWNER_AUTHENTICATION_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "EVIDENCE_EXPIRED" -> HttpStatus.GONE;
            case "RULE_CUTOVER_REVIEW_UNAVAILABLE", "RULE_PAUSE_REVIEW_UNAVAILABLE",
                    "SPATIAL_CIRCLE_UNAVAILABLE",
                    "DEPLOYMENT_BUNDLE_UNAVAILABLE", "DEPLOYMENT_UNAVAILABLE", "WINDOW_MEASURE_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            case "RULE_CUTOVER_REVIEW_OWNER_UNAVAILABLE", "RULE_PAUSE_REVIEW_OWNER_UNAVAILABLE",
                    "DEPLOYMENT_STORAGE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
