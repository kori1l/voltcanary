package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetLineage;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetLineageRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MlDatasetMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(MlDatasetMaterializationService.class);

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlValidationReportRepository validationReportRepository;
    private final MlDatasetLineageRepository lineageRepository;
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MlDatasetValidationService validationService;
    private final MlDatasetLabelReadinessService labelReadinessService;
    private final MlDatasetPreviewService previewService;
    private final MlDatasetSupportService support;

    public MlDatasetMaterializationService(
            MlDatasetRepository datasetRepository,
            MlDatasetVersionRepository datasetVersionRepository,
            MlValidationReportRepository validationReportRepository,
            MlDatasetLineageRepository lineageRepository,
            MlCanonicalMetricRowRepository canonicalMetricRowRepository,
            MlDatasetValidationService validationService,
            MlDatasetLabelReadinessService labelReadinessService,
            MlDatasetPreviewService previewService,
            MlDatasetSupportService support) {
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.validationReportRepository = validationReportRepository;
        this.lineageRepository = lineageRepository;
        this.canonicalMetricRowRepository = canonicalMetricRowRepository;
        this.validationService = validationService;
        this.labelReadinessService = labelReadinessService;
        this.previewService = previewService;
        this.support = support;
    }

    @Transactional
    public MlInternalDatasetResponse createFromInternalTelemetry(
            UUID accountId,
            UUID userId,
            MlInternalDatasetRequest request) {
        validationService.validateCreateRequest(request);
        MlDatasetValidationResult validation = validationService.validateTelemetry(request);
        assertPreviewFresh(accountId, request, validation);

        Instant now = Instant.now();
        MlDataset dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        dataset.setAccountId(accountId);
        dataset.setName(request.name().trim());
        dataset.setDescription(request.description());
        dataset.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        dataset.setCreatedBy(userId);
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
        datasetRepository.save(dataset);

        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(dataset.getId());
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(validation.ready() ? MlDatasetStatus.VALIDATING : MlDatasetStatus.FAILED);
        version.setSplitStrategy(support.normalizedSplitStrategy(request.splitStrategy()));
        version.setSplitConfigJson(support.blankToDefault(request.splitConfigJson(), MlDatasetSupportService.DEFAULT_SPLIT_CONFIG));
        version.setFeatureConfigVersion(support.blankToDefault(request.featureConfigVersion(), "feature-config-v1"));
        version.setSourceWindowStart(request.from());
        version.setSourceWindowEnd(request.to());
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        version.setReadyAt(null);
        version.setFailureReason(validation.ready() ? null : String.join("; ", validation.messages()));
        datasetVersionRepository.save(version);
        if (validation.ready()) {
            try {
                canonicalMetricRowRepository.saveAll(canonicalRows(accountId, version.getId(), request, validation.extractedRows(), now));
                version.setStatus(MlDatasetStatus.READY);
                version.setReadyAt(now);
                version.setImmutableRowReference(immutableRowReference(version.getId()));
                version.setDataHash(dataHash(validation.extractedRows()));
                datasetVersionRepository.save(version);
            } catch (RuntimeException exception) {
                version.setStatus(MlDatasetStatus.FAILED);
                version.setReadyAt(null);
                version.setFailureReason("Internal telemetry materialization failed.");
                version.setImmutableRowReference(null);
                version.setDataHash(null);
                datasetVersionRepository.save(version);
            }
        }

        MlValidationReport report = new MlValidationReport();
        report.setId(UUID.randomUUID());
        report.setDatasetVersionId(version.getId());
        report.setAcceptedRows(validation.acceptedRows());
        report.setRejectedRows(validation.rejectedRows());
        report.setWarningCount(validation.warningCount());
        report.setRejectedReasonsJson(support.writeJson(validation.rejectedReasons()));
        report.setMappingStatsJson(support.writeJson(validation.mappingStats()));
        report.setQualityProfileJson(support.writeJson(qualityProfileWithPreview(accountId, request, validation)));
        report.setSummary(version.getStatus() == MlDatasetStatus.READY
                ? "Telemetry selection is ready for training."
                : "Telemetry selection failed validation.");
        report.setCreatedAt(now);
        validationReportRepository.save(report);

        MlDatasetLineage lineage = new MlDatasetLineage();
        lineage.setId(UUID.randomUUID());
        lineage.setDatasetVersionId(version.getId());
        lineage.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        lineage.setSourceReference("telemetry-service:/telemetry/devices/{deviceId}/readings");
        lineage.setFilterJson(support.writeJson(lineageFilter(request, validation)));
        lineage.setSelectedDevicesJson(support.writeJson(request.deviceIds()));
        lineage.setSelectedMetricsJson(support.writeJson(request.metrics()));
        lineage.setSourceWindowStart(request.from());
        lineage.setSourceWindowEnd(request.to());
        lineage.setContentHash(support.sha256(lineage.getFilterJson() + lineage.getSelectedDevicesJson() + lineage.getSelectedMetricsJson()));
        lineage.setCreatedAt(now);
        lineageRepository.save(lineage);
        log.info("mlDatasetCreated datasetId={} datasetVersionId={} accountId={} sourceType={} status={} acceptedRows={} rejectedRows={} warningCount={}",
                dataset.getId(), version.getId(), accountId, version.getSourceType(), version.getStatus(),
                report.getAcceptedRows(), report.getRejectedRows(), report.getWarningCount());

        return new MlInternalDatasetResponse(
                dataset.getId(),
                version.getId(),
                accountId,
                version.getVersion(),
                version.getStatus(),
                version.getSplitStrategy(),
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                validation.messages(),
                version.getCreatedAt(),
                version.getReadyAt());
    }

    private void assertPreviewFresh(UUID accountId, MlInternalDatasetRequest request, MlDatasetValidationResult validation) {
        if (support.isBlank(request.previewHash()) && support.isBlank(request.previewId())) {
            return;
        }
        if (support.isBlank(request.previewHash())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "previewHash is required when previewId is supplied");
        }
        MlDatasetLabelReadinessDto labelReadiness = labelReadinessService.labelReadiness(
                accountId,
                support.normalizedModelFamily(request.modelFamily()),
                request.horizonDays(),
                request.from(),
                request.to(),
                validation);
        String currentHash = previewService.previewHash(accountId, request, validation, labelReadiness);
        if (!request.previewHash().equals(currentHash)) {
            Map<String, Object> dependency = new LinkedHashMap<>();
            dependency.put("type", "preview");
            dependency.put("id", support.isBlank(request.previewId()) ? support.previewId(request.previewHash()) : request.previewId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", "PREVIEW_STALE");
            details.put("submittedPreviewHash", request.previewHash());
            details.put("currentPreviewHash", currentHash);
            details.put("validationMessages", validation.messages());
            dependency.put("details", details);
            throw new MlLifecycleConflictException(
                    "PREVIEW_STALE",
                    "Dataset preview is stale; request a fresh preview before creating.",
                    dependency);
        }
    }

    private Map<String, Object> qualityProfileWithPreview(
            UUID accountId,
            MlInternalDatasetRequest request,
            MlDatasetValidationResult validation) {
        Map<String, Object> qualityProfile = new LinkedHashMap<>(validation.qualityProfile());
        MlDatasetLabelReadinessDto labelReadiness = labelReadinessService.labelReadiness(
                accountId,
                support.normalizedModelFamily(request.modelFamily()),
                request.horizonDays(),
                request.from(),
                request.to(),
                validation);
        if (labelReadiness != null) {
            qualityProfile.put("labelCoverage", support.labelReadinessMap(labelReadiness));
        }
        if (!support.isBlank(request.previewHash()) || !support.isBlank(request.previewId())) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("previewId", support.isBlank(request.previewId()) ? support.previewId(request.previewHash()) : request.previewId());
            preview.put("previewHash", request.previewHash());
            preview.put("currentPreviewHash", previewService.previewHash(accountId, request, validation, labelReadiness));
            preview.put("modelFamily", support.normalizedModelFamily(request.modelFamily()).name());
            preview.put("horizonDays", request.horizonDays());
            preview.put("metricSelectionMode", support.normalizedMetricSelectionMode(request.metricSelectionMode()).name());
            preview.put("expiresMatchedAtCreate", true);
            if (labelReadiness != null) {
                preview.put("labelReadiness", support.labelReadinessMap(labelReadiness));
            }
            qualityProfile.put("selectedPreview", preview);
        }
        return qualityProfile;
    }

    private Map<String, Object> lineageFilter(MlInternalDatasetRequest request, MlDatasetValidationResult validation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("from", request.from().toString());
        value.put("to", request.to().toString());
        value.put("deviceType", request.deviceType());
        value.put("deviceMetadata", request.deviceMetadata() == null ? Map.of() : request.deviceMetadata());
        value.put("maintenanceWindowIdsByDevice", validation.maintenanceWindowIdsByDevice());
        value.put("minSamplesPerMetric", support.minSamplesPerMetric(request.minSamplesPerMetric()));
        value.put("maxSamplingGapSeconds", support.maxSamplingGapSeconds(request.maxSamplingGapSeconds()));
        value.put("previewId", request.previewId());
        value.put("previewHash", request.previewHash());
        value.put("modelFamily", support.normalizedModelFamily(request.modelFamily()).name());
        value.put("horizonDays", request.horizonDays());
        value.put("metricSelectionMode", support.normalizedMetricSelectionMode(request.metricSelectionMode()).name());
        value.put("validationMessages", validation.messages());
        value.put("materializedRowCount", validation.extractedRows().size());
        value.put("materializedRowPreview", validation.extractedRows().stream()
                .limit(10)
                .map(row -> Map.of(
                        "deviceId", row.deviceId().toString(),
                        "metricName", row.metricName(),
                        "canonicalMetricName", row.canonicalMetricName(),
                        "rawUnit", row.rawUnit() == null ? "" : row.rawUnit(),
                        "canonicalUnit", row.canonicalUnit() == null ? "" : row.canonicalUnit(),
                        "timestamp", row.timestamp().toString()))
                .toList());
        return value;
    }

    private List<MlCanonicalMetricRow> canonicalRows(
            UUID accountId,
            UUID datasetVersionId,
            MlInternalDatasetRequest request,
            List<MlDatasetTelemetryRow> rows,
            Instant now) {
        return rows.stream()
                .map(row -> {
                    MlCanonicalMetricRow canonical = new MlCanonicalMetricRow();
                    canonical.setId(UUID.randomUUID());
                    canonical.setAccountId(accountId);
                    canonical.setDatasetVersionId(datasetVersionId);
                    canonical.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
                    canonical.setDeviceId(row.deviceId());
                    canonical.setExternalDeviceKey(null);
                    canonical.setVirtualDeviceKey("internal:" + accountId + ":" + row.deviceId());
                    canonical.setDeviceType(request.deviceType());
                    canonical.setSourceId("telemetry-service");
                    canonical.setSensorId(row.metricName());
                    canonical.setTimestamp(row.timestamp());
                    canonical.setMetricName(row.metricName());
                    canonical.setCanonicalMetricName(row.canonicalMetricName());
                    canonical.setNumericValue(row.numericValue());
                    canonical.setRawUnit(row.rawUnit());
                    canonical.setCanonicalUnit(row.canonicalUnit());
                    canonical.setQualityFlags("[]");
                    canonical.setMetadataJson(support.writeJson(request.deviceMetadata() == null ? Map.of() : request.deviceMetadata()));
                    canonical.setSourceLineage(support.writeJson(Map.of(
                            "source", "internal_telemetry",
                            "deviceId", row.deviceId().toString(),
                            "metricName", row.metricName(),
                            "timestamp", row.timestamp().toString())));
                    canonical.setCreatedAt(now);
                    return canonical;
                })
                .toList();
    }

    private String immutableRowReference(UUID datasetVersionId) {
        return "ml_canonical_metric_rows:dataset_version_id=" + datasetVersionId;
    }

    private String dataHash(List<MlDatasetTelemetryRow> rows) {
        StringBuilder builder = new StringBuilder();
        for (MlDatasetTelemetryRow row : rows) {
            builder.append(row.deviceId()).append('|')
                    .append(row.metricName()).append('|')
                    .append(row.canonicalMetricName()).append('|')
                    .append(row.rawUnit() == null ? "" : row.rawUnit()).append('|')
                    .append(row.canonicalUnit() == null ? "" : row.canonicalUnit()).append('|')
                    .append(row.timestamp()).append('|')
                    .append(Double.toString(row.numericValue()))
                    .append('\n');
        }
        return support.sha256(builder.toString());
    }
}
