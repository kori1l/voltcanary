package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlExternalDeviceMapping;
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
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
import com.voltcanary.analytics.service.MlExternalImportParser.ImportResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MlExternalDatasetImportService {

    private static final long MAX_CSV_BYTES = 10 * 1024 * 1024;
    private static final String DEFAULT_EXTERNAL_SPLIT_CONFIG =
            "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}";

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlImportedFileRepository importedFileRepository;
    private final MlImportedMetricRowRepository rowRepository;
    private final MlExternalDeviceMappingRepository deviceMappingRepository;
    private final MlValidationReportRepository validationReportRepository;
    private final MlExternalImportParser parser;
    private final MlDatasetWrites writes;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NEVER)
    public MlExternalImportResponse importCsv(
            UUID accountId,
            UUID userId,
            String datasetName,
            String description,
            MultipartFile file,
            MlCsvSchemaMapping mapping) {
        return importFile(accountId, userId, datasetName, description, file, mapping, false);
    }

    @Transactional(propagation = Propagation.NEVER)
    public MlExternalImportResponse importJsonl(
            UUID accountId,
            UUID userId,
            String datasetName,
            String description,
            MultipartFile file,
            MlCsvSchemaMapping mapping) {
        return importFile(accountId, userId, datasetName, description, file, mapping, true);
    }

    private MlExternalImportResponse importFile(
            UUID accountId,
            UUID userId,
            String datasetName,
            String description,
            MultipartFile file,
            MlCsvSchemaMapping mapping,
            boolean jsonl) {
        validateFile(file);
        MlImportSchema.validateMapping(mapping);
        byte[] content = readBytes(file);
        String contentHash = sha256(content);
        Instant now = Instant.now();
        var dataset = createDataset(accountId, userId, datasetName, description, file, now);
        String mediaType = jsonl ? "application/x-ndjson" : "text/csv";
        var importedFile =
                createImportedFile(
                        accountId,
                        userId,
                        dataset.getId(),
                        file,
                        content,
                        contentHash,
                        mapping,
                        mediaType,
                        now);
        Map<UUID, UUID> owners = new LinkedHashMap<>();
        ImportResult result =
                jsonl
                        ? parser.parseJsonlRows(
                                accountId, importedFile.getId(), content, mapping, now, owners)
                        : parser.parseRows(
                                accountId, importedFile.getId(), content, mapping, now, owners);
        if (Boolean.FALSE.equals(mapping.allowPartialAcceptance()) && result.rejectedRows() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Import contains rejected rows and partial acceptance is disabled");
        }
        var response =
                writes.commit(
                        () -> {
                            datasetRepository.save(dataset);
                            importedFileRepository.save(importedFile);
                            persistImportResult(importedFile.getId(), result);
                            var version =
                                    createExternalDatasetVersion(
                                            accountId,
                                            userId,
                                            dataset.getId(),
                                            contentHash,
                                            mapping,
                                            result,
                                            now);
                            var report =
                                    createReport(
                                            version.getId(),
                                            importedFile.getId(),
                                            result,
                                            now,
                                            jsonl ? "JSONL import" : "CSV import");
                            return new MlExternalImportResponse(
                                    dataset.getId(),
                                    version.getId(),
                                    importedFile.getId(),
                                    report.getId(),
                                    contentHash,
                                    report.getAcceptedRows(),
                                    report.getRejectedRows(),
                                    report.getWarningCount(),
                                    result.messages(),
                                    now);
                        });
        log.info(
                "mlExternalImportCompleted importedFileId={} datasetId={} accountId={} mediaType={}"
                        + " acceptedRows={} rejectedRows={}",
                importedFile.getId(),
                dataset.getId(),
                accountId,
                mediaType,
                response.acceptedRows(),
                response.rejectedRows());
        return response;
    }

    private void persistImportResult(UUID importedFileId, ImportResult result) {
        rowRepository.saveAll(result.acceptedRows());
        Map<String, MlExternalDeviceMapping> mappings = new LinkedHashMap<>();
        for (MlImportedMetricRow row : result.acceptedRows()) {
            mappings.computeIfAbsent(
                    row.getExternalDeviceKey(),
                    key -> {
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

    private MlDataset createDataset(
            UUID accountId,
            UUID userId,
            String datasetName,
            String description,
            MultipartFile file,
            Instant now) {
        MlDataset dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        dataset.setAccountId(accountId);
        dataset.setName(
                datasetName == null || datasetName.isBlank()
                        ? file.getOriginalFilename()
                        : datasetName.trim());
        dataset.setDescription(description);
        dataset.setSourceType(MlDatasetSourceType.EXTERNAL_IMPORT);
        dataset.setCreatedBy(userId);
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
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
        importedFile.setOriginalFilename(
                file.getOriginalFilename() == null ? "dataset" : file.getOriginalFilename());
        importedFile.setContentHash(contentHash);
        importedFile.setContentType(
                file.getContentType() == null ? defaultContentType : file.getContentType());
        importedFile.setSizeBytes(content.length);
        importedFile.setMappingVersion(
                mapping.mappingVersion() == null || mapping.mappingVersion().isBlank()
                        ? "external-mapping-v1"
                        : mapping.mappingVersion());
        importedFile.setMappingJson(writeJson(mapping));
        importedFile.setArtifactUri(null);
        importedFile.setUploadedBy(userId);
        importedFile.setImportedAt(now);
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
        version.setStatus(
                result.acceptedRows().isEmpty()
                        ? MlDatasetStatus.FAILED
                        : MlDatasetStatus.VALIDATING);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson(DEFAULT_EXTERNAL_SPLIT_CONFIG);
        version.setFeatureConfigVersion(
                mapping.mappingVersion() == null || mapping.mappingVersion().isBlank()
                        ? "external-mapping-v1"
                        : mapping.mappingVersion());
        version.setArtifactHash(contentHash);
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        version.setFailureReason(
                result.acceptedRows().isEmpty()
                        ? "External import produced no accepted rows."
                        : null);
        datasetVersionRepository.save(version);
        return version;
    }

    private MlValidationReport createReport(
            UUID datasetVersionId,
            UUID importedFileId,
            ImportResult result,
            Instant now,
            String source) {
        MlValidationReport report = new MlValidationReport();
        report.setId(UUID.randomUUID());
        report.setDatasetVersionId(datasetVersionId);
        report.setImportedFileId(importedFileId);
        report.setAcceptedRows(result.acceptedRows().size());
        report.setRejectedRows(result.rejectedRows());
        report.setWarningCount(result.warningCount());
        report.setRejectedReasonsJson(writeJson(result.rejectedReasons()));
        report.setMappingStatsJson(writeJson(result.mappingStats()));
        report.setQualityProfileJson(writeJson(MlImportQuality.qualityProfile(result)));
        report.setSummary(
                result.rejectedRows() == 0
                        ? source + " accepted all rows."
                        : source + " accepted rows with validation rejects.");
        report.setCreatedAt(now);
        validationReportRepository.save(report);
        return report;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "CSV import file is required");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE, "CSV import file exceeds 10 MiB");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not read CSV import file", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML import metadata");
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
}
