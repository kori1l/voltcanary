package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlExternalDeviceMapping;
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlCsvSchemaMapping;
import com.voltcanary.analytics.dto.MlExternalImportResponse;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlExternalDeviceMappingRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlExternalDatasetImportService {

    private static final Logger log = LoggerFactory.getLogger(MlExternalDatasetImportService.class);

    private static final long MAX_CSV_BYTES = 10 * 1024 * 1024;
    private static final String DEFAULT_EXTERNAL_SPLIT_CONFIG = "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}";

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlImportedFileRepository importedFileRepository;
    private final MlImportedMetricRowRepository rowRepository;
    private final MlExternalDeviceMappingRepository deviceMappingRepository;
    private final MlValidationReportRepository validationReportRepository;
    private final AnalyticsAccessService accessService;
    private final ObjectMapper objectMapper;

    public MlExternalDatasetImportService(
            MlDatasetRepository datasetRepository,
            MlDatasetVersionRepository datasetVersionRepository,
            MlImportedFileRepository importedFileRepository,
            MlImportedMetricRowRepository rowRepository,
            MlExternalDeviceMappingRepository deviceMappingRepository,
            MlValidationReportRepository validationReportRepository,
            AnalyticsAccessService accessService,
            ObjectMapper objectMapper) {
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.importedFileRepository = importedFileRepository;
        this.rowRepository = rowRepository;
        this.deviceMappingRepository = deviceMappingRepository;
        this.validationReportRepository = validationReportRepository;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MlExternalImportResponse importCsv(
            UUID accountId,
            UUID userId,
            String datasetName,
            String description,
            MultipartFile file,
            MlCsvSchemaMapping mapping) {
        validateFile(file);
        validateMapping(mapping);

        byte[] content = readBytes(file);
        String contentHash = sha256(content);
        Instant now = Instant.now();

        MlDataset dataset = createDataset(accountId, userId, datasetName, description, file, now);
        MlImportedFile importedFile = createImportedFile(accountId, userId, dataset.getId(), file, content, contentHash, mapping, "text/csv", now);

        ImportResult result = parseRows(accountId, importedFile.getId(), content, mapping, now);
        persistImportResult(importedFile.getId(), result);
        MlDatasetVersion datasetVersion = createExternalDatasetVersion(accountId, userId, dataset.getId(), contentHash, mapping, result, now);
        MlValidationReport report = createReport(datasetVersion.getId(), importedFile.getId(), result, now, "CSV import");

        if (Boolean.FALSE.equals(mapping.allowPartialAcceptance()) && result.rejectedRows() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV import contains rejected rows and partial acceptance is disabled");
        }
        log.info("mlExternalImportCompleted importedFileId={} datasetId={} accountId={} mediaType={} acceptedRows={} rejectedRows={} warningCount={} contentHash={}",
                importedFile.getId(), dataset.getId(), accountId, "text/csv", result.acceptedRows().size(), result.rejectedRows(), result.warningCount(), contentHash);

        return new MlExternalImportResponse(
                dataset.getId(),
                datasetVersion.getId(),
                importedFile.getId(),
                report.getId(),
                contentHash,
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                result.messages(),
                now);
    }

    @Transactional
    public MlExternalImportResponse importJsonl(
            UUID accountId,
            UUID userId,
            String datasetName,
            String description,
            MultipartFile file,
            MlCsvSchemaMapping mapping) {
        validateFile(file);
        validateMapping(mapping);

        byte[] content = readBytes(file);
        String contentHash = sha256(content);
        Instant now = Instant.now();

        MlDataset dataset = createDataset(accountId, userId, datasetName, description, file, now);
        MlImportedFile importedFile = createImportedFile(accountId, userId, dataset.getId(), file, content, contentHash, mapping, "application/x-ndjson", now);
        ImportResult result = parseJsonlRows(accountId, importedFile.getId(), content, mapping, now);
        persistImportResult(importedFile.getId(), result);
        MlDatasetVersion datasetVersion = createExternalDatasetVersion(accountId, userId, dataset.getId(), contentHash, mapping, result, now);

        MlValidationReport report = createReport(datasetVersion.getId(), importedFile.getId(), result, now, "JSONL import");
        if (Boolean.FALSE.equals(mapping.allowPartialAcceptance()) && result.rejectedRows() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSONL import contains rejected rows and partial acceptance is disabled");
        }
        log.info("mlExternalImportCompleted importedFileId={} datasetId={} accountId={} mediaType={} acceptedRows={} rejectedRows={} warningCount={} contentHash={}",
                importedFile.getId(), dataset.getId(), accountId, "application/x-ndjson", result.acceptedRows().size(), result.rejectedRows(), result.warningCount(), contentHash);

        return new MlExternalImportResponse(
                dataset.getId(),
                datasetVersion.getId(),
                importedFile.getId(),
                report.getId(),
                contentHash,
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                result.messages(),
                now);
    }

    private ImportResult parseRows(UUID accountId, UUID importedFileId, byte[] content, MlCsvSchemaMapping mapping, Instant now) {
        List<MlImportedMetricRow> accepted = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, Long> rejectedReasons = new LinkedHashMap<>();
        Map<String, Long> mappingStats = new LinkedHashMap<>();
        long rejected = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV import file is empty");
            }
            List<String> headers = parseCsvLine(headerLine);
            Map<String, Integer> indexes = indexes(headers);
            requireMappedColumns(indexes, mapping);
            rejectCredentialLikeColumns(headers);

            String line;
            long rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                List<String> row = parseCsvLine(line);
                try {
                    MlImportedMetricRow acceptedRow = toImportedRow(accountId, importedFileId, rowNumber, row, indexes, mapping, now);
                    accepted.add(acceptedRow);
                    increment(mappingStats, "accepted:" + acceptedRow.getMetricName());
                } catch (IllegalArgumentException exception) {
                    rejected++;
                    increment(rejectedReasons, exception.getMessage());
                    messages.add("Row " + rowNumber + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV import file", exception);
        }
        return new ImportResult(accepted, rejected, 0, rejectedReasons, mappingStats, List.copyOf(messages));
    }

    private ImportResult parseJsonlRows(UUID accountId, UUID importedFileId, byte[] content, MlCsvSchemaMapping mapping, Instant now) {
        List<MlImportedMetricRow> accepted = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, Long> rejectedReasons = new LinkedHashMap<>();
        Map<String, Long> mappingStats = new LinkedHashMap<>();
        long rejected = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            long rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> row = objectMapper.readValue(line, new TypeReference<>() {});
                    MlImportedMetricRow acceptedRow = toImportedRow(accountId, importedFileId, rowNumber, row, mapping, now);
                    accepted.add(acceptedRow);
                    increment(mappingStats, "accepted:" + acceptedRow.getMetricName());
                } catch (IllegalArgumentException exception) {
                    rejected++;
                    increment(rejectedReasons, exception.getMessage());
                    messages.add("Row " + rowNumber + ": " + exception.getMessage());
                } catch (JsonProcessingException exception) {
                    rejected++;
                    increment(rejectedReasons, "INVALID_JSONL");
                    messages.add("Row " + rowNumber + ": INVALID_JSONL");
                }
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read JSONL import file", exception);
        }
        return new ImportResult(accepted, rejected, 0, rejectedReasons, mappingStats, List.copyOf(messages));
    }

    private MlImportedMetricRow toImportedRow(
            UUID accountId,
            UUID importedFileId,
            long rowNumber,
            List<String> row,
            Map<String, Integer> indexes,
            MlCsvSchemaMapping mapping,
            Instant now) {
        String deviceKey = cell(row, indexes, mapping.deviceKeyColumn());
        String metricName = cell(row, indexes, mapping.metricNameColumn());
        String rawValue = cell(row, indexes, mapping.metricValueColumn());
        if (deviceKey.isBlank()) {
            throw new IllegalArgumentException("MISSING_DEVICE_KEY");
        }
        if (metricName.isBlank()) {
            throw new IllegalArgumentException("MISSING_METRIC_NAME");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(cell(row, indexes, mapping.timestampColumn()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_TIMESTAMP");
        }
        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_METRIC_VALUE");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("NON_FINITE_METRIC_VALUE");
        }
        String unit = mapping.unitColumn() == null ? null : blankToNull(cell(row, indexes, mapping.unitColumn()));
        validateMetricUnit(metricName, unit);

        MlImportedMetricRow importedRow = new MlImportedMetricRow();
        importedRow.setId(UUID.randomUUID());
        importedRow.setAccountId(accountId);
        importedRow.setImportedFileId(importedFileId);
        importedRow.setExternalDeviceKey(deviceKey);
        applyDeviceMatch(importedRow, accountId, deviceKey,
                mapping.existingDeviceIdColumn() == null ? "" : cell(row, indexes, mapping.existingDeviceIdColumn()),
                mapping.deviceTypeColumn() == null ? "" : cell(row, indexes, mapping.deviceTypeColumn()));
        importedRow.setTimestamp(timestamp);
        importedRow.setMetricName(metricName);
        importedRow.setNumericValue(value);
        importedRow.setUnit(unit);
        importedRow.setLabel(parseLabel(mapping.labelColumn() == null ? "" : cell(row, indexes, mapping.labelColumn())));
        importedRow.setMetadataJson(writeJson(metadata(row, indexes, mapping)));
        importedRow.setSourceRowNumber(rowNumber);
        importedRow.setCreatedAt(now);
        return importedRow;
    }

    private MlImportedMetricRow toImportedRow(
            UUID accountId,
            UUID importedFileId,
            long rowNumber,
            Map<String, Object> row,
            MlCsvSchemaMapping mapping,
            Instant now) {
        String deviceKey = field(row, mapping.deviceKeyColumn());
        String metricName = field(row, mapping.metricNameColumn());
        String rawValue = field(row, mapping.metricValueColumn());
        if (deviceKey.isBlank()) {
            throw new IllegalArgumentException("MISSING_DEVICE_KEY");
        }
        if (metricName.isBlank()) {
            throw new IllegalArgumentException("MISSING_METRIC_NAME");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(field(row, mapping.timestampColumn()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_TIMESTAMP");
        }
        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_METRIC_VALUE");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("NON_FINITE_METRIC_VALUE");
        }
        String unit = mapping.unitColumn() == null ? null : blankToNull(field(row, mapping.unitColumn()));
        validateMetricUnit(metricName, unit);

        MlImportedMetricRow importedRow = new MlImportedMetricRow();
        importedRow.setId(UUID.randomUUID());
        importedRow.setAccountId(accountId);
        importedRow.setImportedFileId(importedFileId);
        importedRow.setExternalDeviceKey(deviceKey);
        applyDeviceMatch(importedRow, accountId, deviceKey,
                mapping.existingDeviceIdColumn() == null ? "" : field(row, mapping.existingDeviceIdColumn()),
                mapping.deviceTypeColumn() == null ? "" : field(row, mapping.deviceTypeColumn()));
        importedRow.setTimestamp(timestamp);
        importedRow.setMetricName(metricName);
        importedRow.setNumericValue(value);
        importedRow.setUnit(unit);
        importedRow.setLabel(parseLabel(mapping.labelColumn() == null ? "" : field(row, mapping.labelColumn())));
        importedRow.setMetadataJson(writeJson(metadata(row, mapping)));
        importedRow.setSourceRowNumber(rowNumber);
        importedRow.setCreatedAt(now);
        return importedRow;
    }

    private Map<String, String> metadata(List<String> row, Map<String, Integer> indexes, MlCsvSchemaMapping mapping) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (mapping.metadataColumns() == null) {
            return metadata;
        }
        for (String column : mapping.metadataColumns()) {
            metadata.put(column, cell(row, indexes, column));
        }
        return metadata;
    }

    private Map<String, String> metadata(Map<String, Object> row, MlCsvSchemaMapping mapping) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (mapping.metadataColumns() == null) {
            return metadata;
        }
        for (String column : mapping.metadataColumns()) {
            metadata.put(column, field(row, column));
        }
        return metadata;
    }

    private void applyDeviceMatch(
            MlImportedMetricRow row,
            UUID accountId,
            String externalDeviceKey,
            String existingDeviceId,
            String deviceType) {
        row.setVirtualDeviceKey("virtual:" + accountId + ":" + externalDeviceKey);
        row.setDeviceType(blankToNull(deviceType));
        if (existingDeviceId == null || existingDeviceId.isBlank()) {
            return;
        }
        try {
            UUID matchedDeviceId = UUID.fromString(existingDeviceId.trim());
            UUID ownerAccountId = accessService.accountIdForDevice(matchedDeviceId, null);
            if (!accountId.equals(ownerAccountId)) {
                throw new IllegalArgumentException("DEVICE_OWNERSHIP_MISMATCH");
            }
            row.setMatchedDeviceId(matchedDeviceId);
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException
                    && "DEVICE_OWNERSHIP_MISMATCH".equals(illegalArgumentException.getMessage())) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("INVALID_EXISTING_DEVICE_ID");
        }
    }

    private void persistImportResult(UUID importedFileId, ImportResult result) {
        rowRepository.saveAll(result.acceptedRows());
        Map<String, MlExternalDeviceMapping> mappings = new LinkedHashMap<>();
        for (MlImportedMetricRow row : result.acceptedRows()) {
            mappings.computeIfAbsent(row.getExternalDeviceKey(), key -> {
                MlExternalDeviceMapping mapping = new MlExternalDeviceMapping();
                mapping.setId(UUID.randomUUID());
                mapping.setAccountId(row.getAccountId());
                mapping.setImportedFileId(importedFileId);
                mapping.setExternalDeviceKey(key);
                mapping.setMatchedDeviceId(row.getMatchedDeviceId());
                mapping.setVirtualDeviceKey(row.getVirtualDeviceKey());
                mapping.setDeviceType(row.getDeviceType());
                mapping.setCreatedAt(row.getCreatedAt());
                return mapping;
            });
        }
        deviceMappingRepository.saveAll(mappings.values());
    }

    private MlDataset createDataset(UUID accountId, UUID userId, String datasetName, String description, MultipartFile file, Instant now) {
        MlDataset dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        dataset.setAccountId(accountId);
        dataset.setName(datasetName == null || datasetName.isBlank() ? file.getOriginalFilename() : datasetName.trim());
        dataset.setDescription(description);
        dataset.setSourceType(MlDatasetSourceType.EXTERNAL_IMPORT);
        dataset.setCreatedBy(userId);
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
        datasetRepository.save(dataset);
        return dataset;
    }

    private MlImportedFile createImportedFile(
            UUID accountId,
            UUID userId,
            UUID datasetId,
            MultipartFile file,
            byte[] content,
            String contentHash,
            MlCsvSchemaMapping mapping,
            String defaultContentType,
            Instant now) {
        MlImportedFile importedFile = new MlImportedFile();
        importedFile.setId(UUID.randomUUID());
        importedFile.setAccountId(accountId);
        importedFile.setDatasetId(datasetId);
        importedFile.setOriginalFilename(file.getOriginalFilename() == null ? "dataset" : file.getOriginalFilename());
        importedFile.setContentHash(contentHash);
        importedFile.setContentType(file.getContentType() == null ? defaultContentType : file.getContentType());
        importedFile.setSizeBytes(content.length);
        importedFile.setMappingVersion(mapping.mappingVersion() == null || mapping.mappingVersion().isBlank()
                ? "external-mapping-v1"
                : mapping.mappingVersion());
        importedFile.setMappingJson(writeJson(mapping));
        importedFile.setArtifactUri(null);
        importedFile.setUploadedBy(userId);
        importedFile.setImportedAt(now);
        importedFileRepository.save(importedFile);
        return importedFile;
    }

    private MlDatasetVersion createExternalDatasetVersion(
            UUID accountId,
            UUID userId,
            UUID datasetId,
            String contentHash,
            MlCsvSchemaMapping mapping,
            ImportResult result,
            Instant now) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(datasetId);
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.EXTERNAL_IMPORT);
        version.setStatus(result.acceptedRows().isEmpty() ? MlDatasetStatus.FAILED : MlDatasetStatus.VALIDATING);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson(DEFAULT_EXTERNAL_SPLIT_CONFIG);
        version.setFeatureConfigVersion(mapping.mappingVersion() == null || mapping.mappingVersion().isBlank()
                ? "external-mapping-v1"
                : mapping.mappingVersion());
        version.setArtifactHash(contentHash);
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        version.setFailureReason(result.acceptedRows().isEmpty()
                ? "External import produced no accepted rows."
                : null);
        datasetVersionRepository.save(version);
        return version;
    }

    private MlValidationReport createReport(UUID datasetVersionId, UUID importedFileId, ImportResult result, Instant now, String source) {
        MlValidationReport report = new MlValidationReport();
        report.setId(UUID.randomUUID());
        report.setDatasetVersionId(datasetVersionId);
        report.setImportedFileId(importedFileId);
        report.setAcceptedRows(result.acceptedRows().size());
        report.setRejectedRows(result.rejectedRows());
        report.setWarningCount(result.warningCount());
        report.setRejectedReasonsJson(writeJson(result.rejectedReasons()));
        report.setMappingStatsJson(writeJson(result.mappingStats()));
        report.setQualityProfileJson(writeJson(qualityProfile(result)));
        report.setSummary(result.rejectedRows() == 0 ? source + " accepted all rows." : source + " accepted rows with validation rejects.");
        report.setCreatedAt(now);
        validationReportRepository.save(report);
        return report;
    }

    private Map<String, Object> qualityProfile(ImportResult result) {
        List<MlImportedMetricRow> rows = result.acceptedRows();
        Map<String, Long> coverageByDeviceMetric = new LinkedHashMap<>();
        for (MlImportedMetricRow row : rows) {
            increment(coverageByDeviceMetric, row.getExternalDeviceKey() + ":" + row.getMetricName());
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("rowCount", rows.size());
        profile.put("rejectedRows", result.rejectedRows());
        profile.put("deviceCount", rows.stream().map(MlImportedMetricRow::getExternalDeviceKey).distinct().count());
        profile.put("metricCount", rows.stream().map(MlImportedMetricRow::getMetricName).distinct().count());
        profile.put("timeRange", importTimeRange(rows));
        profile.put("coverageByDeviceMetric", coverageByDeviceMetric);
        profile.put("maxSamplingGapSeconds", maxSamplingGapSeconds(rows));
        profile.put("duplicateTimestampRate", duplicateTimestampRate(rows));
        profile.put("nonFiniteImpossibleValueCount",
                result.rejectedReasons().getOrDefault("NON_FINITE_METRIC_VALUE", 0L) + impossibleValueCount(rows));
        profile.put("unitNormalizationWarnings", result.rejectedReasons().getOrDefault("METRIC_UNIT_MISMATCH", 0L));
        profile.put("maintenanceOverlapCount", 0L);
        profile.put("labelCoverage", Map.of());
        return profile;
    }

    private long maxSamplingGapSeconds(List<MlImportedMetricRow> rows) {
        Map<String, List<MlImportedMetricRow>> groups = new LinkedHashMap<>();
        for (MlImportedMetricRow row : rows) {
            groups.computeIfAbsent(row.getExternalDeviceKey() + ":" + row.getMetricName(), ignored -> new ArrayList<>()).add(row);
        }
        long max = 0;
        for (List<MlImportedMetricRow> group : groups.values()) {
            List<MlImportedMetricRow> sorted = group.stream()
                    .sorted(Comparator.comparing(MlImportedMetricRow::getTimestamp))
                    .toList();
            for (int index = 1; index < sorted.size(); index++) {
                long gap = java.time.Duration.between(sorted.get(index - 1).getTimestamp(), sorted.get(index).getTimestamp()).abs().toSeconds();
                max = Math.max(max, gap);
            }
        }
        return max;
    }

    private double duplicateTimestampRate(List<MlImportedMetricRow> rows) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        Set<String> seen = new HashSet<>();
        long duplicates = 0;
        for (MlImportedMetricRow row : rows) {
            String key = row.getExternalDeviceKey() + "|" + row.getMetricName() + "|" + row.getTimestamp();
            if (!seen.add(key)) {
                duplicates++;
            }
        }
        return (double) duplicates / rows.size();
    }

    private long impossibleValueCount(List<MlImportedMetricRow> rows) {
        return rows.stream()
                .filter(row -> isKnownNonNegativeMetric(row.getMetricName()) && row.getNumericValue() < 0.0)
                .count();
    }

    private boolean isKnownNonNegativeMetric(String metricName) {
        return expectedUnit(metricName) != null;
    }

    private Map<String, String> importTimeRange(List<MlImportedMetricRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Instant min = rows.stream().map(MlImportedMetricRow::getTimestamp).min(Instant::compareTo).orElseThrow();
        Instant max = rows.stream().map(MlImportedMetricRow::getTimestamp).max(Instant::compareTo).orElseThrow();
        return Map.of("from", min.toString(), "to", max.toString());
    }

    private MlLabelType parseLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MlLabelType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_LABEL");
        }
    }

    private void validateMetricUnit(String metricName, String unit) {
        if (unit == null || unit.isBlank()) {
            return;
        }
        String expected = expectedUnit(metricName);
        if (expected != null && !expected.equals(unit.trim())) {
            throw new IllegalArgumentException("METRIC_UNIT_MISMATCH");
        }
    }

    private String expectedUnit(String metricName) {
        String metric = metricName == null ? "" : metricName.trim();
        return switch (metric) {
            case "activePowerW", "active_power_w", "active_power" -> "W";
            case "voltage", "voltageV", "voltage_v" -> "V";
            case "current", "currentA", "current_a" -> "A";
            case "energyGrowth", "energy_growth", "energyGrowthWh", "energy_growth_wh" -> "Wh";
            case "temperature", "temperatureC", "temperature_c" -> "C";
            case "vibration", "vibrationMmS", "vibration_mm_s" -> "mm/s";
            case "runtime", "runtimeHours", "runtime_hours" -> "h";
            case "powerFactor", "power_factor" -> "ratio";
            case "frequency", "frequencyHz", "frequency_hz" -> "Hz";
            default -> null;
        };
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV import file is required");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV import file exceeds 10 MiB");
        }
    }

    private void validateMapping(MlCsvSchemaMapping mapping) {
        if (mapping == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV schema mapping is required");
        }
        rejectCredentialLikeColumns(List.of(
                mapping.timestampColumn(),
                mapping.deviceKeyColumn(),
                mapping.metricNameColumn(),
                mapping.metricValueColumn()));
        if (mapping.metadataColumns() != null) {
            rejectCredentialLikeColumns(mapping.metadataColumns());
        }
        List<String> optionalColumns = new ArrayList<>();
        optionalColumns.add(mapping.existingDeviceIdColumn());
        optionalColumns.add(mapping.deviceTypeColumn());
        rejectCredentialLikeColumns(optionalColumns);
    }

    private void requireMappedColumns(Map<String, Integer> indexes, MlCsvSchemaMapping mapping) {
        for (String column : List.of(mapping.timestampColumn(), mapping.deviceKeyColumn(), mapping.metricNameColumn(), mapping.metricValueColumn())) {
            if (!indexes.containsKey(column)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV column is missing: " + column);
            }
        }
    }

    private void rejectCredentialLikeColumns(List<String> columns) {
        for (String column : columns) {
            if (column == null) {
                continue;
            }
            String normalized = column.toLowerCase(Locale.ROOT);
            if (normalized.contains("password")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("credential")
                    || normalized.contains("api_key")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV mapping rejects credential-like fields");
            }
        }
    }

    private Map<String, Integer> indexes(List<String> headers) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    private String cell(List<String> row, Map<String, Integer> indexes, String column) {
        Integer index = indexes.get(column);
        if (index == null || index >= row.size()) {
            return "";
        }
        return row.get(index).trim();
    }

    private String field(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        values.add(current.toString());
        return values;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV import file", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML import metadata");
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void increment(Map<String, Long> values, String key) {
        values.merge(key, 1L, Long::sum);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ImportResult(
            List<MlImportedMetricRow> acceptedRows,
            long rejectedRows,
            long warningCount,
            Map<String, Long> rejectedReasons,
            Map<String, Long> mappingStats,
            List<String> messages) {
    }
}
