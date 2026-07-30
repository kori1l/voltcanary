package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetLineage;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlDatasetDetailDto;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.dto.MlLineageDto;
import com.voltcanary.analytics.dto.MlValidationReportDto;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.repository.MlDatasetLineageRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlDatasetService {

    private static final Logger log = LoggerFactory.getLogger(MlDatasetService.class);

    private static final int DEFAULT_MIN_SAMPLES_PER_METRIC = 24;
    private static final long DEFAULT_MAX_SAMPLING_GAP_SECONDS = 6 * 60 * 60;

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlValidationReportRepository validationReportRepository;
    private final MlDatasetLineageRepository lineageRepository;
    private final MaintenanceWindowReadModelRepository maintenanceWindowRepository;
    private final TelemetryClient telemetryClient;
    private final ObjectMapper objectMapper;

    public MlDatasetService(
            MlDatasetRepository datasetRepository,
            MlDatasetVersionRepository datasetVersionRepository,
            MlValidationReportRepository validationReportRepository,
            MlDatasetLineageRepository lineageRepository,
            MaintenanceWindowReadModelRepository maintenanceWindowRepository,
            TelemetryClient telemetryClient,
            ObjectMapper objectMapper) {
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.validationReportRepository = validationReportRepository;
        this.lineageRepository = lineageRepository;
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.telemetryClient = telemetryClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MlInternalDatasetResponse createFromInternalTelemetry(
            UUID accountId,
            UUID userId,
            MlInternalDatasetRequest request) {
        validateRequest(request);

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

        ValidationResult validation = validateTelemetry(request);
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(dataset.getId());
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(validation.ready() ? MlDatasetStatus.READY : MlDatasetStatus.FAILED);
        version.setSplitStrategy(request.splitStrategy() == null ? MlTrainingSplitStrategy.TIME_BASED : request.splitStrategy());
        version.setSplitConfigJson(blankToDefault(request.splitConfigJson(), "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}"));
        version.setFeatureConfigVersion(blankToDefault(request.featureConfigVersion(), "feature-config-v1"));
        version.setSourceWindowStart(request.from());
        version.setSourceWindowEnd(request.to());
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        version.setReadyAt(validation.ready() ? now : null);
        version.setFailureReason(validation.ready() ? null : String.join("; ", validation.messages()));
        datasetVersionRepository.save(version);

        MlValidationReport report = new MlValidationReport();
        report.setId(UUID.randomUUID());
        report.setDatasetVersionId(version.getId());
        report.setAcceptedRows(validation.acceptedRows());
        report.setRejectedRows(validation.rejectedRows());
        report.setWarningCount(validation.warningCount());
        report.setRejectedReasonsJson(writeJson(validation.rejectedReasons()));
        report.setMappingStatsJson(writeJson(validation.mappingStats()));
        report.setSummary(validation.ready() ? "Telemetry selection is ready for training." : "Telemetry selection failed validation.");
        report.setCreatedAt(now);
        validationReportRepository.save(report);

        MlDatasetLineage lineage = new MlDatasetLineage();
        lineage.setId(UUID.randomUUID());
        lineage.setDatasetVersionId(version.getId());
        lineage.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        lineage.setSourceReference("telemetry-service:/telemetry/devices/{deviceId}/readings");
        lineage.setFilterJson(writeJson(lineageFilter(request, validation)));
        lineage.setSelectedDevicesJson(writeJson(request.deviceIds()));
        lineage.setSelectedMetricsJson(writeJson(request.metrics()));
        lineage.setSourceWindowStart(request.from());
        lineage.setSourceWindowEnd(request.to());
        lineage.setContentHash(sha256(lineage.getFilterJson() + lineage.getSelectedDevicesJson() + lineage.getSelectedMetricsJson()));
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

    @Transactional(readOnly = true)
    public List<MlDatasetSummaryDto> list(UUID accountId) {
        return datasetRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(dataset -> datasetVersionRepository.findByDatasetIdOrderByVersionDesc(dataset.getId()).stream()
                        .findFirst()
                        .map(version -> summary(dataset, version)))
                .flatMap(Optional::stream)
                .toList();
    }

    @Transactional(readOnly = true)
    public MlDatasetDetailDto detail(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion version = datasetVersionRepository.findById(datasetVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found"));
        if (!accountId.equals(version.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        MlDataset dataset = datasetRepository.findById(version.getDatasetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset not found"));
        return new MlDatasetDetailDto(
                summary(dataset, version),
                validationReportRepository.findByDatasetVersionIdOrderByCreatedAtDesc(version.getId()).stream()
                        .map(this::validationReport)
                        .toList(),
                lineageRepository.findByDatasetVersionId(version.getId()).stream()
                        .map(this::lineage)
                        .toList());
    }

    private void validateRequest(MlInternalDatasetRequest request) {
        if (!request.to().isAfter(request.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset time window must end after it starts");
        }
        if (request.metrics().stream().anyMatch(metric -> metric == null || metric.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metrics must not be blank");
        }
        if (request.deviceIds().stream().distinct().count() != request.deviceIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device ids must be unique");
        }
    }

    private ValidationResult validateTelemetry(MlInternalDatasetRequest request) {
        int minSamples = request.minSamplesPerMetric() == null ? DEFAULT_MIN_SAMPLES_PER_METRIC : request.minSamplesPerMetric();
        long maxGapSeconds = request.maxSamplingGapSeconds() == null
                ? DEFAULT_MAX_SAMPLING_GAP_SECONDS
                : request.maxSamplingGapSeconds();
        long acceptedRows = 0;
        long rejectedRows = 0;
        long warnings = 0;
        List<String> messages = new ArrayList<>();
        Map<String, Long> rejectedReasons = new LinkedHashMap<>();
        Map<String, Long> mappingStats = new LinkedHashMap<>();
        Map<UUID, List<UUID>> maintenanceWindowIdsByDevice = new LinkedHashMap<>();

        for (UUID deviceId : request.deviceIds()) {
            List<MaintenanceWindowReadModel> maintenanceWindows =
                    maintenanceWindowRepository.findActiveOverlapping(deviceId, request.from(), request.to());
            if (!maintenanceWindows.isEmpty()) {
                maintenanceWindowIdsByDevice.put(deviceId, maintenanceWindows.stream()
                        .map(MaintenanceWindowReadModel::getMaintenanceWindowId)
                        .toList());
                warnings++;
                messages.add("Device " + deviceId + " overlaps " + maintenanceWindows.size() + " maintenance windows.");
            }
            for (String metric : request.metrics()) {
                TelemetryChartResponse readings;
                try {
                    readings = telemetryClient.readings(deviceId, metric, request.from(), request.to());
                } catch (ResponseStatusException exception) {
                    if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                        throw exception;
                    }
                    rejectedRows += minSamples;
                    increment(rejectedReasons, "UNSUPPORTED_OR_UNAVAILABLE_METRIC");
                    messages.add("Device " + deviceId + " metric " + metric
                            + " is unsupported or unavailable in the selected window.");
                    continue;
                }
                List<TelemetryChartResponse.Point> points = readings == null || readings.points() == null
                        ? List.of()
                        : readings.points();
                long nonFiniteValues = points.stream().filter(point -> !Double.isFinite(point.value())).count();
                if (nonFiniteValues > 0) {
                    rejectedRows += nonFiniteValues;
                    increment(rejectedReasons, "NON_FINITE_VALUES", nonFiniteValues);
                    messages.add("Device " + deviceId + " metric " + metric + " contains "
                            + nonFiniteValues + " non-finite values.");
                    continue;
                }
                if (points.size() < minSamples) {
                    rejectedRows += minSamples - points.size();
                    increment(rejectedReasons, "INSUFFICIENT_SAMPLES");
                    messages.add("Device " + deviceId + " metric " + metric + " has " + points.size()
                            + " samples; minimum is " + minSamples + ".");
                    continue;
                }
                long maxGap = maxGapSeconds(points);
                if (maxGap > maxGapSeconds) {
                    warnings++;
                    messages.add("Device " + deviceId + " metric " + metric + " max sampling gap is "
                            + maxGap + " seconds.");
                }
                acceptedRows += points.size();
                increment(mappingStats, "accepted:" + metric, points.size());
            }
        }
        if (acceptedRows == 0 && messages.isEmpty()) {
            messages.add("No telemetry rows matched the requested dataset filters.");
        }
        return new ValidationResult(acceptedRows > 0 && rejectedReasons.isEmpty(),
                acceptedRows,
                rejectedRows,
                warnings,
                List.copyOf(messages),
                rejectedReasons,
                mappingStats,
                maintenanceWindowIdsByDevice);
    }

    private Map<String, Object> lineageFilter(MlInternalDatasetRequest request, ValidationResult validation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("from", request.from().toString());
        value.put("to", request.to().toString());
        value.put("deviceType", request.deviceType());
        value.put("deviceMetadata", request.deviceMetadata() == null ? Map.of() : request.deviceMetadata());
        value.put("maintenanceWindowIdsByDevice", validation.maintenanceWindowIdsByDevice());
        value.put("minSamplesPerMetric", request.minSamplesPerMetric() == null ? DEFAULT_MIN_SAMPLES_PER_METRIC : request.minSamplesPerMetric());
        value.put("maxSamplingGapSeconds", request.maxSamplingGapSeconds() == null
                ? DEFAULT_MAX_SAMPLING_GAP_SECONDS
                : request.maxSamplingGapSeconds());
        value.put("validationMessages", validation.messages());
        return value;
    }

    private long maxGapSeconds(List<TelemetryChartResponse.Point> points) {
        long max = 0;
        for (int index = 1; index < points.size(); index++) {
            long gap = Math.abs(Duration.between(points.get(index - 1).timestamp(), points.get(index).timestamp()).toSeconds());
            max = Math.max(max, gap);
        }
        return max;
    }

    private void increment(Map<String, Long> values, String key) {
        increment(values, key, 1);
    }

    private void increment(Map<String, Long> values, String key, long amount) {
        values.merge(key, amount, Long::sum);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML dataset metadata");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private MlDatasetSummaryDto summary(MlDataset dataset, MlDatasetVersion version) {
        return new MlDatasetSummaryDto(
                dataset.getId(),
                version.getId(),
                dataset.getName(),
                dataset.getDescription(),
                version.getSourceType(),
                version.getVersion(),
                version.getStatus(),
                version.getSplitStrategy(),
                version.getFeatureConfigVersion(),
                version.getSourceWindowStart(),
                version.getSourceWindowEnd(),
                version.getCreatedAt(),
                version.getReadyAt(),
                version.getFailureReason());
    }

    private MlValidationReportDto validationReport(MlValidationReport report) {
        return new MlValidationReportDto(
                report.getId(),
                report.getDatasetVersionId(),
                report.getImportedFileId(),
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                readLongMap(report.getRejectedReasonsJson()),
                readLongMap(report.getMappingStatsJson()),
                report.getSummary(),
                report.getCreatedAt());
    }

    private MlLineageDto lineage(MlDatasetLineage lineage) {
        return new MlLineageDto(
                lineage.getId(),
                lineage.getDatasetVersionId(),
                lineage.getSourceType(),
                lineage.getSourceReference(),
                lineage.getFilterJson(),
                lineage.getSelectedDevicesJson(),
                lineage.getSelectedMetricsJson(),
                lineage.getSourceWindowStart(),
                lineage.getSourceWindowEnd(),
                lineage.getContentHash());
    }

    private Map<String, Long> readLongMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Long.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private record ValidationResult(
            boolean ready,
            long acceptedRows,
            long rejectedRows,
            long warningCount,
            List<String> messages,
            Map<String, Long> rejectedReasons,
            Map<String, Long> mappingStats,
            Map<UUID, List<UUID>> maintenanceWindowIdsByDevice) {
    }
}
