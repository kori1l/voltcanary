package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.MlLabelAuditDto;
import com.voltcanary.analytics.dto.MlLabelDto;
import com.voltcanary.analytics.dto.MlLabelRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlLabelService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analytics/ml/labels")
public class MlLabelController {

    private final MlLabelService service;
    private final AnalyticsAccessService accessService;

    public MlLabelController(MlLabelService service, AnalyticsAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @GetMapping
    public List<MlLabelDto> list(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam("datasetVersionId") UUID datasetVersionId) {
        return service.list(resolveAccountId(accountId, userId, correlationId), datasetVersionId);
    }

    @PostMapping
    public MlLabelDto create(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlLabelRequest request) {
        requireWrite(role);
        return service.create(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), request);
    }

    @PutMapping("/{labelId}")
    public MlLabelDto correct(
            @PathVariable("labelId") UUID labelId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlLabelRequest request) {
        requireWrite(role);
        return service.correct(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), labelId, request);
    }

    @GetMapping("/{labelId}/audit")
    public List<MlLabelAuditDto> audit(
            @PathVariable("labelId") UUID labelId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.audit(resolveAccountId(accountId, userId, correlationId), labelId);
    }

    @GetMapping("/{labelId}/lifecycle-impact")
    public MlLifecycleImpactResponse lifecycleImpact(
            @PathVariable("labelId") UUID labelId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "action", defaultValue = "delete") String action) {
        return service.impact(resolveAccountId(accountId, userId, correlationId), labelId, action);
    }

    @PostMapping("/{labelId}/delete")
    public MlLabelDto delete(
            @PathVariable("labelId") UUID labelId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        return service.delete(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), labelId, request);
    }

    private UUID resolveAccountId(UUID accountId, String userId, String correlationId) {
        return accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
    }

    private void requireWrite(String role) {
        if (!("ADMIN".equals(role) || "OWNER".equals(role) || "USER".equals(role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ML label write access denied");
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
