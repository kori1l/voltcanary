package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.dto.MlCsvSchemaMapping;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MlExternalImportParser {

    private final AnalyticsAccessService accessService;
    private final MlImportCsv csv;
    private final ObjectMapper objectMapper;

    public ImportResult parseRows(
        UUID accountId,
        UUID importedFileId,
        byte[] content,
        MlCsvSchemaMapping mapping,
        Instant now,
        Map<UUID, UUID> owners
    ) {
        List<MlImportedMetricRow> accepted = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, Long> rejectedReasons = new LinkedHashMap<>();
        Map<String, Long> mappingStats = new LinkedHashMap<>();
        long rejected = 0;

        var table = csv.read(content);
        Map<String, Integer> indexes = MlImportSchema.indexes(table.headers());
        MlImportSchema.requireMappedColumns(indexes, mapping);
        MlImportSchema.rejectCredentialLikeColumns(table.headers());
        for (var record : table.records()) {
            long rowNumber = record.getRecordNumber() + 1;
            try {
                if (record.size() != table.headers().size()) throw new IllegalArgumentException(
                    "CSV_COLUMN_COUNT_MISMATCH"
                );
                var row = toImportedRow(
                    accountId,
                    importedFileId,
                    rowNumber,
                    record.toList(),
                    indexes,
                    mapping,
                    now,
                    owners
                );
                accepted.add(row);
                MlImportSchema.increment(mappingStats, "accepted:" + row.getMetricName());
            } catch (IllegalArgumentException exception) {
                rejected++;
                MlImportSchema.increment(rejectedReasons, exception.getMessage());
                messages.add("Row " + rowNumber + ": " + exception.getMessage());
            }
        }
        return new ImportResult(
            accepted,
            rejected,
            0,
            rejectedReasons,
            mappingStats,
            List.copyOf(messages)
        );
    }

    public ImportResult parseJsonlRows(
        UUID accountId,
        UUID importedFileId,
        byte[] content,
        MlCsvSchemaMapping mapping,
        Instant now,
        Map<UUID, UUID> owners
    ) {
        List<MlImportedMetricRow> accepted = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, Long> rejectedReasons = new LinkedHashMap<>();
        Map<String, Long> mappingStats = new LinkedHashMap<>();
        long rejected = 0;

        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new java.io.ByteArrayInputStream(content),
                    StandardCharsets.UTF_8
                )
            )
        ) {
            String line;
            long rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> row = objectMapper.readValue(
                        line,
                        new TypeReference<>() {}
                    );
                    MlImportedMetricRow acceptedRow = toImportedRow(
                        accountId,
                        importedFileId,
                        rowNumber,
                        row,
                        mapping,
                        now,
                        owners
                    );
                    accepted.add(acceptedRow);
                    MlImportSchema.increment(
                        mappingStats,
                        "accepted:" + acceptedRow.getMetricName()
                    );
                } catch (IllegalArgumentException exception) {
                    rejected++;
                    MlImportSchema.increment(rejectedReasons, exception.getMessage());
                    messages.add("Row " + rowNumber + ": " + exception.getMessage());
                } catch (JsonProcessingException exception) {
                    rejected++;
                    MlImportSchema.increment(rejectedReasons, "INVALID_JSONL");
                    messages.add("Row " + rowNumber + ": INVALID_JSONL");
                }
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not read JSONL import file",
                exception
            );
        }
        return new ImportResult(
            accepted,
            rejected,
            0,
            rejectedReasons,
            mappingStats,
            List.copyOf(messages)
        );
    }

    public MlImportedMetricRow toImportedRow(
        UUID accountId,
        UUID importedFileId,
        long rowNumber,
        List<String> row,
        Map<String, Integer> indexes,
        MlCsvSchemaMapping mapping,
        Instant now,
        Map<UUID, UUID> owners
    ) {
        String deviceKey = MlImportSchema.cell(row, indexes, mapping.deviceKeyColumn());
        String metricName = MlImportSchema.cell(row, indexes, mapping.metricNameColumn());
        String rawValue = MlImportSchema.cell(row, indexes, mapping.metricValueColumn());
        if (deviceKey.isBlank()) {
            throw new IllegalArgumentException("MISSING_DEVICE_KEY");
        }
        if (metricName.isBlank()) {
            throw new IllegalArgumentException("MISSING_METRIC_NAME");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(MlImportSchema.cell(row, indexes, mapping.timestampColumn()));
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
        String unit =
            mapping.unitColumn() == null
                ? null
                : MlImportSchema.blankToNull(
                      MlImportSchema.cell(row, indexes, mapping.unitColumn())
                  );
        MlImportSchema.validateMetricUnit(metricName, unit);

        MlImportedMetricRow importedRow = new MlImportedMetricRow();
        importedRow.setId(UUID.randomUUID());
        importedRow.setAccountId(accountId);
        importedRow.setImportedFileId(importedFileId);
        importedRow.setExternalDeviceKey(deviceKey);
        applyDeviceMatch(
            importedRow,
            accountId,
            deviceKey,
            mapping.existingDeviceIdColumn() == null
                ? ""
                : MlImportSchema.cell(row, indexes, mapping.existingDeviceIdColumn()),
            mapping.deviceTypeColumn() == null
                ? ""
                : MlImportSchema.cell(row, indexes, mapping.deviceTypeColumn()),
            owners
        );
        importedRow.setTimestamp(timestamp);
        importedRow.setMetricName(metricName);
        importedRow.setNumericValue(value);
        importedRow.setUnit(unit);
        importedRow.setLabel(
            MlImportSchema.parseLabel(
                mapping.labelColumn() == null
                    ? ""
                    : MlImportSchema.cell(row, indexes, mapping.labelColumn())
            )
        );
        importedRow.setMetadataJson(writeJson(metadata(row, indexes, mapping)));
        importedRow.setSourceRowNumber(rowNumber);
        importedRow.setCreatedAt(now);
        return importedRow;
    }

    public MlImportedMetricRow toImportedRow(
        UUID accountId,
        UUID importedFileId,
        long rowNumber,
        Map<String, Object> row,
        MlCsvSchemaMapping mapping,
        Instant now,
        Map<UUID, UUID> owners
    ) {
        String deviceKey = MlImportSchema.field(row, mapping.deviceKeyColumn());
        String metricName = MlImportSchema.field(row, mapping.metricNameColumn());
        String rawValue = MlImportSchema.field(row, mapping.metricValueColumn());
        if (deviceKey.isBlank()) {
            throw new IllegalArgumentException("MISSING_DEVICE_KEY");
        }
        if (metricName.isBlank()) {
            throw new IllegalArgumentException("MISSING_METRIC_NAME");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(MlImportSchema.field(row, mapping.timestampColumn()));
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
        String unit =
            mapping.unitColumn() == null
                ? null
                : MlImportSchema.blankToNull(MlImportSchema.field(row, mapping.unitColumn()));
        MlImportSchema.validateMetricUnit(metricName, unit);

        MlImportedMetricRow importedRow = new MlImportedMetricRow();
        importedRow.setId(UUID.randomUUID());
        importedRow.setAccountId(accountId);
        importedRow.setImportedFileId(importedFileId);
        importedRow.setExternalDeviceKey(deviceKey);
        applyDeviceMatch(
            importedRow,
            accountId,
            deviceKey,
            mapping.existingDeviceIdColumn() == null
                ? ""
                : MlImportSchema.field(row, mapping.existingDeviceIdColumn()),
            mapping.deviceTypeColumn() == null
                ? ""
                : MlImportSchema.field(row, mapping.deviceTypeColumn()),
            owners
        );
        importedRow.setTimestamp(timestamp);
        importedRow.setMetricName(metricName);
        importedRow.setNumericValue(value);
        importedRow.setUnit(unit);
        importedRow.setLabel(
            MlImportSchema.parseLabel(
                mapping.labelColumn() == null
                    ? ""
                    : MlImportSchema.field(row, mapping.labelColumn())
            )
        );
        importedRow.setMetadataJson(writeJson(metadata(row, mapping)));
        importedRow.setSourceRowNumber(rowNumber);
        importedRow.setCreatedAt(now);
        return importedRow;
    }

    public Map<String, String> metadata(
        List<String> row,
        Map<String, Integer> indexes,
        MlCsvSchemaMapping mapping
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (mapping.metadataColumns() == null) {
            return metadata;
        }
        for (String column : mapping.metadataColumns()) {
            metadata.put(column, MlImportSchema.cell(row, indexes, column));
        }
        return metadata;
    }

    public Map<String, String> metadata(Map<String, Object> row, MlCsvSchemaMapping mapping) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (mapping.metadataColumns() == null) {
            return metadata;
        }
        for (String column : mapping.metadataColumns()) {
            metadata.put(column, MlImportSchema.field(row, column));
        }
        return metadata;
    }

    public void applyDeviceMatch(
        MlImportedMetricRow row,
        UUID accountId,
        String externalDeviceKey,
        String existingDeviceId,
        String deviceType,
        Map<UUID, UUID> owners
    ) {
        row.setVirtualDeviceKey("virtual:" + accountId + ":" + externalDeviceKey);
        row.setDeviceType(MlImportSchema.blankToNull(deviceType));
        if (existingDeviceId == null || existingDeviceId.isBlank()) {
            return;
        }
        UUID matchedDeviceId;
        try {
            matchedDeviceId = UUID.fromString(existingDeviceId.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("INVALID_EXISTING_DEVICE_ID");
        }
        UUID ownerAccountId = owners.computeIfAbsent(matchedDeviceId, id ->
            accessService.accountIdForDevice(id, null)
        );
        if (!accountId.equals(ownerAccountId)) throw new IllegalArgumentException(
            "DEVICE_OWNERSHIP_MISMATCH"
        );
        row.setMatchedDeviceId(matchedDeviceId);
    }

    public record ImportResult(
        List<MlImportedMetricRow> acceptedRows,
        long rejectedRows,
        long warningCount,
        Map<String, Long> rejectedReasons,
        Map<String, Long> mappingStats,
        List<String> messages
    ) {}

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not serialize ML import metadata",
                failure
            );
        }
    }
}
