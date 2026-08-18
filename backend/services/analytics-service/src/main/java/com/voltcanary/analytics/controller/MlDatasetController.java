package com.voltcanary.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.dto.MlCsvSchemaMapping;
import com.voltcanary.analytics.dto.MlDatasetDetailDto;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlExternalImportResponse;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationRequest;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResponse;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlDatasetService;
import com.voltcanary.analytics.service.MlExternalDatasetImportService;
import com.voltcanary.analytics.service.MlFeaturePipelineService;
import com.voltcanary.analytics.service.MlHorizonLabelGenerationService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analytics/ml/datasets")
public class MlDatasetController {

    private final MlDatasetService service;
    private final MlExternalDatasetImportService importService;
    private final MlFeaturePipelineService featurePipelineService;
    private final MlHorizonLabelGenerationService horizonLabelGenerationService;
    private final AnalyticsAccessService accessService;
    private final ObjectMapper objectMapper;

    public MlDatasetController(
            MlDatasetService service,
            MlExternalDatasetImportService importService,
            MlFeaturePipelineService featurePipelineService,
            MlHorizonLabelGenerationService horizonLabelGenerationService,
            AnalyticsAccessService accessService,
            ObjectMapper objectMapper) {
        this.service = service;
        this.importService = importService;
        this.featurePipelineService = featurePipelineService;
        this.horizonLabelGenerationService = horizonLabelGenerationService;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<MlDatasetSummaryDto> listDatasets(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "statusFilter", defaultValue = "default") String statusFilter) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return service.list(resolvedAccountId, statusFilter);
    }

    @GetMapping("/versions/{datasetVersionId}")
    public MlDatasetDetailDto getDatasetVersion(
            @PathVariable("datasetVersionId") UUID datasetVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return service.detail(resolvedAccountId, datasetVersionId);
    }

    @PostMapping("/versions/{datasetVersionId}/archive")
    public MlDatasetSummaryDto archiveDatasetVersion(
            @PathVariable("datasetVersionId") UUID datasetVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return service.archive(resolvedAccountId, parseUserId(userId), datasetVersionId, request);
    }

    @GetMapping("/versions/{datasetVersionId}/lifecycle-impact")
    public MlLifecycleImpactResponse datasetLifecycleImpact(
            @PathVariable("datasetVersionId") UUID datasetVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "action", defaultValue = "delete") String action) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return service.impact(resolvedAccountId, datasetVersionId, action);
    }

    @PostMapping("/versions/{datasetVersionId}/delete")
    public MlDatasetSummaryDto deleteDatasetVersion(
            @PathVariable("datasetVersionId") UUID datasetVersionId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return service.delete(resolvedAccountId, parseUserId(userId), datasetVersionId, request);
    }

    @PostMapping("/internal-telemetry")
    public MlInternalDatasetResponse createInternalTelemetryDataset(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlInternalDatasetRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        for (UUID deviceId : request.deviceIds()) {
            accessService.requireReadAccess(deviceId, userId, role, correlationId);
        }
        return service.createFromInternalTelemetry(resolvedAccountId, parseUserId(userId), request);
    }

    @PostMapping("/internal-telemetry/preview")
    public MlDatasetWorkbenchPreviewResponse previewInternalTelemetryDataset(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlDatasetWorkbenchPreviewRequest request) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        for (UUID deviceId : request.deviceIds()) {
            accessService.requireReadAccess(deviceId, userId, role, correlationId);
        }
        return service.previewInternalTelemetryDataset(resolvedAccountId, request);
    }

    @PostMapping(value = "/external-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MlExternalImportResponse importExternalCsv(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("mappingJson") String mappingJson,
            @RequestParam(value = "datasetName", required = false) String datasetName,
            @RequestParam(value = "description", required = false) String description) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return importService.importCsv(
                resolvedAccountId,
                parseUserId(userId),
                datasetName,
                description,
                file,
                parseMapping(mappingJson));
    }

    @PostMapping(value = "/external-jsonl", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MlExternalImportResponse importExternalJsonl(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("mappingJson") String mappingJson,
            @RequestParam(value = "datasetName", required = false) String datasetName,
            @RequestParam(value = "description", required = false) String description) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return importService.importJsonl(
                resolvedAccountId,
                parseUserId(userId),
                datasetName,
                description,
                file,
                parseMapping(mappingJson));
    }

    @PostMapping("/feature-snapshots")
    public MlFeatureSnapshotResponse generateFeatureSnapshot(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlFeatureSnapshotRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        parseUserId(userId);
        return featurePipelineService.generate(resolvedAccountId, request);
    }

    @GetMapping("/feature-snapshots")
    public List<MlFeatureSnapshotResponse> listFeatureSnapshots(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "datasetVersionId", required = false) UUID datasetVersionId) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return featurePipelineService.list(resolvedAccountId, datasetVersionId);
    }

    @PostMapping("/feature-snapshots/{featureSnapshotId}/archive")
    public MlFeatureSnapshotResponse archiveFeatureSnapshot(
            @PathVariable("featureSnapshotId") UUID featureSnapshotId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return featurePipelineService.archive(resolvedAccountId, parseUserId(userId), featureSnapshotId, request);
    }

    @GetMapping("/feature-snapshots/{featureSnapshotId}/lifecycle-impact")
    public MlLifecycleImpactResponse featureSnapshotLifecycleImpact(
            @PathVariable("featureSnapshotId") UUID featureSnapshotId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "action", defaultValue = "delete") String action) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return featurePipelineService.impact(resolvedAccountId, featureSnapshotId, action);
    }

    @PostMapping("/feature-snapshots/{featureSnapshotId}/delete")
    public MlFeatureSnapshotResponse deleteFeatureSnapshot(
            @PathVariable("featureSnapshotId") UUID featureSnapshotId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) MlLifecycleActionRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return featurePipelineService.delete(resolvedAccountId, parseUserId(userId), featureSnapshotId, request);
    }

    @PostMapping("/horizon-labels")
    public ResponseEntity<MlHorizonLabelGenerationResponse> generateHorizonLabels(
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody MlHorizonLabelGenerationRequest request) {
        requireWrite(role);
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return ResponseEntity.accepted().body(horizonLabelGenerationService.start(resolvedAccountId, parseUserId(userId), request));
    }

    @GetMapping("/horizon-labels/{jobId}")
    public MlHorizonLabelGenerationResponse getHorizonLabelGenerationStatus(
            @PathVariable("jobId") UUID jobId,
            @RequestHeader(value = "X-Account-Id", required = false) UUID accountId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID resolvedAccountId = accountId != null ? accountId : accessService.accountIdForUser(userId, correlationId);
        return horizonLabelGenerationService.status(resolvedAccountId, jobId);
    }

    private void requireWrite(String role) {
        if (!("ADMIN".equals(role) || "OWNER".equals(role) || "USER".equals(role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ML dataset write access denied");
        }
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
    }

    private MlCsvSchemaMapping parseMapping(String mappingJson) {
        try {
            return objectMapper.readValue(mappingJson, MlCsvSchemaMapping.class);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CSV schema mapping");
        }
    }
}
