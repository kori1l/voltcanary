package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.RuleEvaluationRequest;
import com.voltcanary.analytics.dto.RuleEvaluationResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.RuleEvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics/rules")
public class RuleEvaluationController {

    private final RuleEvaluationService evaluationService;
    private final AnalyticsAccessService accessService;

    public RuleEvaluationController(RuleEvaluationService evaluationService, AnalyticsAccessService accessService) {
        this.evaluationService = evaluationService;
        this.accessService = accessService;
    }

    @PostMapping("/test")
    public RuleEvaluationResponse test(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) RuleEvaluationRequest request) {
        if (request != null && request.deviceId() != null) {
            accessService.requireReadAccess(request.deviceId(), userId, role, correlationId);
        }
        return evaluationService.evaluate(request);
    }
}
