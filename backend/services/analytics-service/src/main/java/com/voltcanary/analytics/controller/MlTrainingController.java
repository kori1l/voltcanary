package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.MlTrainingRunDto;
import com.voltcanary.analytics.dto.MlTrainingRunRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlTrainingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analytics/ml/training-runs")
public class MlTrainingController {

    private final MlTrainingService service;
    private final AnalyticsAccessService accessService;

    public MlTrainingController(MlTrainingService service, AnalyticsAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping
    public MlTrainingRunDto create(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlTrainingRunRequest request) {
        requireWrite(role);
        return service.create(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), request);
    }

    @GetMapping
    public List<MlTrainingRunDto> list(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "statusFilter", defaultValue = "default") String statusFilter) {
        return service.list(resolveAccountId(accountId, userId, correlationId), statusFilter);
    }

    @PostMapping("/{runId}/cancel")
    public MlTrainingRunDto cancel(
            @PathVariable("runId") UUID runId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        requireWrite(role);
        parseUserId(userId);
        return service.cancel(resolveAccountId(accountId, userId, correlationId), runId);
    }

    @PostMapping("/{runId}/retry")
    public MlTrainingRunDto retry(
            @PathVariable("runId") UUID runId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        requireWrite(role);
        parseUserId(userId);
        return service.retry(resolveAccountId(accountId, userId, correlationId), runId);
    }

    @PostMapping("/{runId}/archive")
    public MlTrainingRunDto archive(
            @PathVariable("runId") UUID runId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        return service.archive(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), runId, request);
    }

    @GetMapping("/{runId}/lifecycle-impact")
    public MlLifecycleImpactResponse lifecycleImpact(
            @PathVariable("runId") UUID runId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "action", defaultValue = "delete") String action) {
        return service.impact(resolveAccountId(accountId, userId, correlationId), runId, action);
    }

    @PostMapping("/{runId}/delete")
    public MlTrainingRunDto delete(
            @PathVariable("runId") UUID runId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        return service.delete(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), runId, request);
    }

    private UUID resolveAccountId(UUID accountId, String userId, String correlationId) {
        return accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
    }

    private void requireWrite(String role) {
        if (!("ADMIN".equals(role) || "OWNER".equals(role) || "USER".equals(role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ML training write access denied");
        }
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
    }
}
