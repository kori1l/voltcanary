package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.dto.MlDeploymentPolicyDto;
import com.voltcanary.analytics.dto.MlDeploymentPolicyRequest;
import com.voltcanary.analytics.dto.MlModelStatusRequest;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlModelRegistryService;
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
@RequestMapping("/analytics/ml")
public class MlModelRegistryController {

    private final MlModelRegistryService service;
    private final AnalyticsAccessService accessService;

    public MlModelRegistryController(MlModelRegistryService service, AnalyticsAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @GetMapping("/models")
    public List<MlModelVersionDto> listModels(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.listModels(resolveAccountId(accountId, userId, correlationId));
    }

    @GetMapping("/models/{modelVersionId}")
    public MlModelVersionDto getModel(
            @PathVariable("modelVersionId") UUID modelVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.getModel(resolveAccountId(accountId, userId, correlationId), modelVersionId);
    }

    @PostMapping("/models/{modelVersionId}/approve")
    public MlModelVersionDto approve(
            @PathVariable("modelVersionId") UUID modelVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlModelStatusRequest request) {
        requireWrite(role);
        return service.approve(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), modelVersionId, request);
    }

    @PostMapping("/models/{modelVersionId}/reject")
    public MlModelVersionDto reject(
            @PathVariable("modelVersionId") UUID modelVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlModelStatusRequest request) {
        requireWrite(role);
        return service.reject(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), modelVersionId, request);
    }

    @PostMapping("/models/{modelVersionId}/archive")
    public MlModelVersionDto archive(
            @PathVariable("modelVersionId") UUID modelVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlModelStatusRequest request) {
        requireWrite(role);
        return service.archive(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), modelVersionId, request);
    }

    @GetMapping("/deployment-policies")
    public List<MlDeploymentPolicyDto> listPolicies(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.listPolicies(resolveAccountId(accountId, userId, correlationId));
    }

    @PostMapping("/deployment-policies")
    public MlDeploymentPolicyDto upsertPolicy(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlDeploymentPolicyRequest request) {
        requireWrite(role);
        return service.upsertPolicy(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), request);
    }

    @PostMapping("/deployment-policies/rollback")
    public MlDeploymentPolicyDto rollbackPolicy(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam("scope") MlDeploymentScope scope,
            @RequestParam(value = "deviceId", required = false) UUID deviceId,
            @RequestParam(value = "deviceType", required = false) String deviceType,
            @RequestParam(value = "reason", required = false) String reason) {
        requireWrite(role);
        return service.rollbackPolicy(resolveAccountId(accountId, userId, correlationId), parseUserId(userId), scope, deviceId, deviceType, reason);
    }

    @GetMapping("/deployment-policies/resolve")
    public MlResolvedDeploymentPolicyResponse resolvePolicy(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "deviceId", required = false) UUID deviceId,
            @RequestParam(value = "deviceType", required = false) String deviceType) {
        return service.resolvePolicy(resolveAccountId(accountId, userId, correlationId), deviceId, deviceType);
    }

    private UUID resolveAccountId(UUID accountId, String userId, String correlationId) {
        return accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
    }

    private void requireWrite(String role) {
        if (!("ADMIN".equals(role) || "OWNER".equals(role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ML registry write access denied");
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
