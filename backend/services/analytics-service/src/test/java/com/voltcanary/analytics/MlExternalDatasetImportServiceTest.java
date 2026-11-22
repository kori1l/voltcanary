package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
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
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlDatasetWrites;
import com.voltcanary.analytics.service.MlExternalDatasetImportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class MlExternalDatasetImportServiceTest {

    private final MlDatasetRepository datasetRepository = mock(MlDatasetRepository.class);
    private final MlDatasetVersionRepository datasetVersionRepository = mock(
        MlDatasetVersionRepository.class
    );
    private final MlImportedFileRepository importedFileRepository = mock(
        MlImportedFileRepository.class
    );
    private final MlImportedMetricRowRepository rowRepository = mock(
        MlImportedMetricRowRepository.class
    );
    private final MlExternalDeviceMappingRepository deviceMappingRepository = mock(
        MlExternalDeviceMappingRepository.class
    );
    private final MlValidationReportRepository reportRepository = mock(
        MlValidationReportRepository.class
    );
    private final AnalyticsAccessService accessService = mock(AnalyticsAccessService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MlExternalDatasetImportService service = new MlExternalDatasetImportService(
        datasetRepository,
        datasetVersionRepository,
        importedFileRepository,
        rowRepository,
        deviceMappingRepository,
        reportRepository,
        new com.voltcanary.analytics.service.MlExternalImportParser(
            accessService,
            new com.voltcanary.analytics.service.MlImportCsv(),
            objectMapper
        ),
        new MlDatasetWrites(
            new AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() {
                    return new Object();
                }

                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                protected void doCommit(DefaultTransactionStatus status) {}

                protected void doRollback(DefaultTransactionStatus status) {}
            }
        ),
        objectMapper
    );

    @Test
    void importsValidCsvRowsWithLabelsAndMetadata() {
        MockMultipartFile file = csv(
            """
            ts,device,metric,value,unit,label,firmware
            2026-07-01T00:00:00Z,washer-1,activePowerW,123.4,W,ANOMALY,2.4.1
            """
        );

        MlExternalImportResponse response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "External washer data",
            null,
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isEqualTo(1);
        assertThat(response.rejectedRows()).isZero();
        assertThat(response.contentHash()).startsWith("sha256:");
        assertThat(response.datasetVersionId()).isNotNull();

        ArgumentCaptor<MlDatasetVersion> version = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(datasetVersionRepository).save(version.capture());
        assertThat(version.getValue().getId()).isEqualTo(response.datasetVersionId());
        assertThat(version.getValue().getDatasetId()).isEqualTo(response.datasetId());
        assertThat(version.getValue().getStatus()).isEqualTo(MlDatasetStatus.VALIDATING);
        assertThat(version.getValue().getSplitStrategy()).isEqualTo(
            MlTrainingSplitStrategy.TIME_BASED
        );
        assertThat(version.getValue().getArtifactHash()).isEqualTo(response.contentHash());

        ArgumentCaptor<Iterable<MlImportedMetricRow>> rows = ArgumentCaptor.forClass(
            Iterable.class
        );
        verify(rowRepository).saveAll(rows.capture());
        MlImportedMetricRow row = rows.getValue().iterator().next();
        assertThat(row.getExternalDeviceKey()).isEqualTo("washer-1");
        assertThat(row.getVirtualDeviceKey()).contains("washer-1");
        assertThat(row.getMetricName()).isEqualTo("activePowerW");
        assertThat(row.getNumericValue()).isEqualTo(123.4);
        assertThat(row.getLabel()).isEqualTo(MlLabelType.ANOMALY);
        assertThat(row.getMetadataJson()).contains("firmware");
    }

    @Test
    void createsExternalDatasetVersionAndReportWithoutReadyStateUntilCanonicalRowsExist() {
        MockMultipartFile file = csv(
            """
            ts,device,metric,value,unit,label,firmware
            2026-07-01T00:00:00Z,washer-1,activePowerW,123.4,W,NORMAL,2.4.1
            2026-07-01T01:00:00Z,washer-1,voltageV,230.0,V,NORMAL,2.4.1
            """
        );

        MlExternalImportResponse response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "External washer data",
            "Historical CSV import",
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isEqualTo(2);
        assertThat(response.rejectedRows()).isZero();
        assertThat(response.datasetVersionId()).isNotNull();

        ArgumentCaptor<MlDatasetVersion> version = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(datasetVersionRepository).save(version.capture());
        assertThat(version.getValue().getId()).isEqualTo(response.datasetVersionId());
        assertThat(version.getValue().getDatasetId()).isEqualTo(response.datasetId());
        assertThat(version.getValue().getStatus()).isEqualTo(MlDatasetStatus.VALIDATING);
        assertThat(version.getValue().getReadyAt()).isNull();
        assertThat(version.getValue().getArtifactUri()).isNull();
        assertThat(version.getValue().getArtifactHash()).isEqualTo(response.contentHash());
        assertThat(version.getValue().getImmutableRowReference()).isNull();
        assertThat(version.getValue().getDataHash()).isNull();

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(
            MlValidationReport.class
        );
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getDatasetVersionId()).isEqualTo(response.datasetVersionId());
        assertThat(report.getValue().getImportedFileId()).isEqualTo(response.importedFileId());
        assertThat(report.getValue().getAcceptedRows()).isEqualTo(2);
        assertThat(report.getValue().getRejectedRows()).isZero();
        assertThat(report.getValue().getQualityProfileJson())
            .contains("coverageByDeviceMetric")
            .contains("maxSamplingGapSeconds")
            .contains("duplicateTimestampRate");
    }

    @Test
    void importsJsonlRowsAndMatchesExistingDeviceIdsWhenProvided() {
        UUID accountId = UUID.randomUUID();
        UUID existingDeviceId = UUID.randomUUID();
        when(accessService.accountIdForDevice(existingDeviceId, null)).thenReturn(accountId);
        MockMultipartFile file = jsonl(
            """
            {"ts":"2026-07-01T00:00:00Z","device":"washer-1","existingDeviceId":"%s","deviceType":"washer","metric":"activePowerW","value":123.4,"unit":"W","label":"NORMAL","firmware":"2.4.1"}
            """.formatted(existingDeviceId)
        );

        MlExternalImportResponse response = service.importJsonl(
            accountId,
            UUID.randomUUID(),
            "External washer data",
            null,
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isEqualTo(1);
        assertThat(response.datasetVersionId()).isNotNull();
        ArgumentCaptor<Iterable<MlImportedMetricRow>> rows = ArgumentCaptor.forClass(
            Iterable.class
        );
        verify(rowRepository).saveAll(rows.capture());
        MlImportedMetricRow row = rows.getValue().iterator().next();
        assertThat(row.getMatchedDeviceId()).isEqualTo(existingDeviceId);
        assertThat(row.getVirtualDeviceKey()).contains("washer-1");
        assertThat(row.getDeviceType()).isEqualTo("washer");
        verify(accessService).accountIdForDevice(existingDeviceId, null);
        verify(deviceMappingRepository).saveAll(any());
    }

    @Test
    void storesValidationReportForRejectedCsvRowsWhenPartialAcceptanceIsAllowed() {
        MockMultipartFile file = csv(
            """
            ts,device,metric,value,unit,label,firmware
            not-a-date,washer-1,activePowerW,123.4,W,NORMAL,2.4.1
            2026-07-01T01:00:00Z,washer-1,activePowerW,NaN,W,NORMAL,2.4.1
            """
        );

        MlExternalImportResponse response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "External washer data",
            null,
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isZero();
        assertThat(response.rejectedRows()).isEqualTo(2);
        assertThat(response.validationMessages()).anySatisfy(message ->
            assertThat(message).contains("INVALID_TIMESTAMP")
        );
        assertThat(response.validationMessages()).anySatisfy(message ->
            assertThat(message).contains("NON_FINITE_METRIC_VALUE")
        );

        ArgumentCaptor<MlDatasetVersion> version = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(datasetVersionRepository).save(version.capture());
        assertThat(version.getValue().getId()).isEqualTo(response.datasetVersionId());
        assertThat(version.getValue().getStatus()).isEqualTo(MlDatasetStatus.FAILED);
        assertThat(version.getValue().getReadyAt()).isNull();
        assertThat(version.getValue().getFailureReason()).contains("no accepted rows");

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(
            MlValidationReport.class
        );
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getDatasetVersionId()).isEqualTo(response.datasetVersionId());
        assertThat(report.getValue().getRejectedReasonsJson())
            .contains("INVALID_TIMESTAMP")
            .contains("NON_FINITE_METRIC_VALUE");
        assertThat(report.getValue().getQualityProfileJson())
            .contains("rowCount")
            .contains("nonFiniteImpossibleValueCount");
    }

    @Test
    void rejectsRowsMappedToDevicesOwnedByAnotherAccount() {
        UUID importingAccountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        UUID existingDeviceId = UUID.randomUUID();
        when(accessService.accountIdForDevice(existingDeviceId, null)).thenReturn(otherAccountId);
        MockMultipartFile file = csv(
            """
            ts,device,metric,value,unit,label,existingDeviceId,deviceType,firmware
            2026-07-01T00:00:00Z,washer-1,activePowerW,123.4,W,NORMAL,%s,washer,2.4.1
            """.formatted(existingDeviceId)
        );

        MlExternalImportResponse response = service.importCsv(
            importingAccountId,
            UUID.randomUUID(),
            "External washer data",
            null,
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isZero();
        assertThat(response.rejectedRows()).isEqualTo(1);
        assertThat(response.validationMessages()).anySatisfy(message ->
            assertThat(message).contains("DEVICE_OWNERSHIP_MISMATCH")
        );

        ArgumentCaptor<MlDatasetVersion> version = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(datasetVersionRepository).save(version.capture());
        assertThat(version.getValue().getStatus()).isEqualTo(MlDatasetStatus.FAILED);
        assertThat(version.getValue().getFailureReason()).contains("no accepted rows");

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(
            MlValidationReport.class
        );
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getDatasetVersionId()).isEqualTo(response.datasetVersionId());
        assertThat(report.getValue().getRejectedReasonsJson()).contains(
            "DEVICE_OWNERSHIP_MISMATCH"
        );
    }

    @Test
    void rejectsRowsWithMetricUnitMismatch() {
        MockMultipartFile file = csv(
            """
            ts,device,metric,value,unit,label,firmware
            2026-07-01T00:00:00Z,washer-1,activePowerW,123.4,V,NORMAL,2.4.1
            """
        );

        MlExternalImportResponse response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "External washer data",
            null,
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isZero();
        assertThat(response.rejectedRows()).isEqualTo(1);
        assertThat(response.validationMessages()).anySatisfy(message ->
            assertThat(message).contains("METRIC_UNIT_MISMATCH")
        );

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(
            MlValidationReport.class
        );
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getRejectedReasonsJson()).contains("METRIC_UNIT_MISMATCH");
        assertThat(report.getValue().getQualityProfileJson()).contains("unitNormalizationWarnings");
    }

    @Test
    void reportsDuplicateSamplingGapAndImpossibleValueQualityMetrics() {
        MockMultipartFile file = csv(
            """
            ts,device,metric,value,unit,label,firmware
            2026-07-01T00:00:00Z,washer-1,activePowerW,123.4,W,NORMAL,2.4.1
            2026-07-01T00:00:00Z,washer-1,activePowerW,124.0,W,NORMAL,2.4.1
            2026-07-01T02:00:00Z,washer-1,activePowerW,-1.0,W,NORMAL,2.4.1
            """
        );

        MlExternalImportResponse response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "External washer data",
            null,
            file,
            mapping()
        );

        assertThat(response.acceptedRows()).isEqualTo(3);

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(
            MlValidationReport.class
        );
        verify(reportRepository).save(report.capture());
        JsonNode quality = readTree(report.getValue().getQualityProfileJson());
        assertThat(quality.path("maxSamplingGapSeconds").asLong()).isEqualTo(7200L);
        assertThat(quality.path("duplicateTimestampRate").asDouble()).isEqualTo(1.0 / 3.0);
        assertThat(quality.path("nonFiniteImpossibleValueCount").asLong()).isEqualTo(1L);
        assertThat(
            quality.path("coverageByDeviceMetric").path("washer-1:activePowerW").asLong()
        ).isEqualTo(3L);
    }

    @Test
    void csvSupportsBomQuotedCommasEscapedQuotesAndMultilineFields() {
        var response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "quoted",
            null,
            csv(
                "\uFEFFts,device,metric,value,unit,label,firmware\r\n" +
                    "2026-07-01T00:00:00Z,washer-1,activePowerW,1,W,NORMAL,\"one," +
                    " \"\"two\"\"\n" +
                    "three\"\r\n"
            ),
            mapping()
        );
        assertThat(response.acceptedRows()).isEqualTo(1);
        ArgumentCaptor<List<MlImportedMetricRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(rowRepository).saveAll(rows.capture());
        assertThat(
            readTree(rows.getValue().getFirst().getMetadataJson()).path("firmware").asText()
        ).isEqualTo("one, \"two\"\nthree");
    }

    @Test
    void malformedCsvOrDuplicateHeadersCannotPersistAnyRecords() {
        for (String content : List.of(
            "ts,device,metric,value,unit,label,firmware\n\"unclosed",
            "ts,device,metric,value,unit,label,firmware,firmware\n"
        )) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.importCsv(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "invalid",
                    null,
                    csv(content),
                    mapping()
                )
            )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error ->
                    assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(
                        400
                    )
                );
        }
        org.mockito.Mockito.verifyNoInteractions(
            datasetRepository,
            importedFileRepository,
            rowRepository
        );
    }

    @Test
    void repeatedDeviceUsesOneOwnershipLookupPerImportForCsvAndJsonl() {
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        when(accessService.accountIdForDevice(device, null)).thenReturn(account);
        String csvRow =
            "2026-07-01T00:00:00Z,washer-1,activePowerW,1,W,NORMAL,firmware," + device + "\n";
        var response = service.importCsv(
            account,
            UUID.randomUUID(),
            "repeat",
            null,
            csv("ts,device,metric,value,unit,label,firmware,existingDeviceId\n" + csvRow.repeat(3)),
            mapping()
        );
        assertThat(response.acceptedRows()).isEqualTo(3);
        verify(accessService, org.mockito.Mockito.times(1)).accountIdForDevice(device, null);
        String jsonRow =
            "{\"ts\":\"2026-07-01T00:00:00Z\",\"device\":\"washer-1\",\"metric\":\"activePowerW\",\"value\":1,\"existingDeviceId\":\"" +
            device +
            "\"}\n";
        assertThat(
            service
                .importJsonl(
                    account,
                    UUID.randomUUID(),
                    "repeat",
                    null,
                    jsonl(jsonRow.repeat(3)),
                    mapping()
                )
                .acceptedRows()
        ).isEqualTo(3);
        verify(accessService, org.mockito.Mockito.times(2)).accountIdForDevice(device, null);
    }

    @Test
    void downstreamOutageAbortsCsvAndJsonlWithoutSavingPartialImport() {
        UUID device = UUID.randomUUID();
        when(accessService.accountIdForDevice(device, null)).thenThrow(
            new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Device ownership unavailable"
            )
        );
        var csvFile = csv(
            "ts,device,metric,value,unit,label,firmware,existingDeviceId\n" +
                "2026-07-01T00:00:00Z,washer-1,activePowerW,1,W,NORMAL,x," +
                device +
                "\n"
        );
        var jsonFile = jsonl(
            "{\"ts\":\"2026-07-01T00:00:00Z\",\"device\":\"washer-1\",\"metric\":\"activePowerW\",\"value\":1,\"existingDeviceId\":\"" +
                device +
                "\"}\n"
        );
        for (boolean json : List.of(false, true)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                if (json) service.importJsonl(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "outage",
                    null,
                    jsonFile,
                    mapping()
                );
                else service.importCsv(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "outage",
                    null,
                    csvFile,
                    mapping()
                );
            })
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error ->
                    assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(
                        503
                    )
                );
        }
        org.mockito.Mockito.verifyNoInteractions(
            datasetRepository,
            importedFileRepository,
            rowRepository
        );
    }

    @Test
    void strictImportRejectsAllRowsBeforeAnyWrite() {
        var strict = new MlCsvSchemaMapping(
            "ts",
            "device",
            "metric",
            "value",
            "unit",
            "label",
            "existingDeviceId",
            "deviceType",
            List.of("firmware"),
            "strict-v1",
            false
        );
        var file = csv(
            "ts,device,metric,value,unit,label,firmware\n" +
                "2026-07-01T00:00:00Z,washer-1,activePowerW,1,W,NORMAL,x\n" +
                "bad-time,washer-1,activePowerW,1,W,NORMAL,x\n"
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.importCsv(UUID.randomUUID(), UUID.randomUUID(), "strict", null, file, strict)
        ).isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(
            datasetRepository,
            importedFileRepository,
            rowRepository
        );
    }

    @Test
    void invalidUuidRemainsARowErrorWithoutCallingDeviceService() {
        var response = service.importCsv(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "invalid-id",
            null,
            csv(
                "ts,device,metric,value,unit,label,firmware,existingDeviceId\n" +
                    "2026-07-01T00:00:00Z,washer,activePowerW,1,W,NORMAL,x,not-a-uuid\n"
            ),
            mapping()
        );
        assertThat(response.rejectedRows()).isEqualTo(1);
        assertThat(response.validationMessages()).anySatisfy(message ->
            assertThat(message).contains("INVALID_EXISTING_DEVICE_ID")
        );
        org.mockito.Mockito.verifyNoInteractions(accessService);
    }

    private MlCsvSchemaMapping mapping() {
        return new MlCsvSchemaMapping(
            "ts",
            "device",
            "metric",
            "value",
            "unit",
            "label",
            "existingDeviceId",
            "deviceType",
            List.of("firmware"),
            "test-mapping-v1",
            true
        );
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile(
            "file",
            "dataset.csv",
            "text/csv",
            content.stripIndent().getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile jsonl(String content) {
        return new MockMultipartFile(
            "file",
            "dataset.jsonl",
            "application/x-ndjson",
            content.stripIndent().getBytes(StandardCharsets.UTF_8)
        );
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new AssertionError("Expected valid JSON", exception);
        }
    }
}
