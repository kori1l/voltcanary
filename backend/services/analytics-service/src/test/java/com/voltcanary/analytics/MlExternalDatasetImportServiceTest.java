package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlCsvSchemaMapping;
import com.voltcanary.analytics.dto.MlExternalImportResponse;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlExternalDeviceMappingRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import com.voltcanary.analytics.service.MlExternalDatasetImportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class MlExternalDatasetImportServiceTest {

    private final MlDatasetRepository datasetRepository = mock(MlDatasetRepository.class);
    private final MlImportedFileRepository importedFileRepository = mock(MlImportedFileRepository.class);
    private final MlImportedMetricRowRepository rowRepository = mock(MlImportedMetricRowRepository.class);
    private final MlExternalDeviceMappingRepository deviceMappingRepository = mock(MlExternalDeviceMappingRepository.class);
    private final MlValidationReportRepository reportRepository = mock(MlValidationReportRepository.class);
    private final MlExternalDatasetImportService service = new MlExternalDatasetImportService(
            datasetRepository,
            importedFileRepository,
            rowRepository,
            deviceMappingRepository,
            reportRepository,
            new ObjectMapper());

    @Test
    void importsValidCsvRowsWithLabelsAndMetadata() {
        MockMultipartFile file = csv("""
                ts,device,metric,value,unit,label,firmware
                2026-07-01T00:00:00Z,washer-1,activePowerW,123.4,W,ANOMALY,2.4.1
                """);

        MlExternalImportResponse response = service.importCsv(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "External washer data",
                null,
                file,
                mapping());

        assertThat(response.acceptedRows()).isEqualTo(1);
        assertThat(response.rejectedRows()).isZero();
        assertThat(response.contentHash()).startsWith("sha256:");

        ArgumentCaptor<Iterable<MlImportedMetricRow>> rows = ArgumentCaptor.forClass(Iterable.class);
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
    void importsJsonlRowsAndMatchesExistingDeviceIdsWhenProvided() {
        UUID existingDeviceId = UUID.randomUUID();
        MockMultipartFile file = jsonl("""
                {"ts":"2026-07-01T00:00:00Z","device":"washer-1","existingDeviceId":"%s","deviceType":"washer","metric":"activePowerW","value":123.4,"unit":"W","label":"NORMAL","firmware":"2.4.1"}
                """.formatted(existingDeviceId));

        MlExternalImportResponse response = service.importJsonl(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "External washer data",
                null,
                file,
                mapping());

        assertThat(response.acceptedRows()).isEqualTo(1);
        ArgumentCaptor<Iterable<MlImportedMetricRow>> rows = ArgumentCaptor.forClass(Iterable.class);
        verify(rowRepository).saveAll(rows.capture());
        MlImportedMetricRow row = rows.getValue().iterator().next();
        assertThat(row.getMatchedDeviceId()).isEqualTo(existingDeviceId);
        assertThat(row.getVirtualDeviceKey()).contains("washer-1");
        assertThat(row.getDeviceType()).isEqualTo("washer");
        verify(deviceMappingRepository).saveAll(any());
    }

    @Test
    void storesValidationReportForRejectedCsvRowsWhenPartialAcceptanceIsAllowed() {
        MockMultipartFile file = csv("""
                ts,device,metric,value,unit,label,firmware
                not-a-date,washer-1,activePowerW,123.4,W,NORMAL,2.4.1
                2026-07-01T01:00:00Z,washer-1,activePowerW,NaN,W,NORMAL,2.4.1
                """);

        MlExternalImportResponse response = service.importCsv(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "External washer data",
                null,
                file,
                mapping());

        assertThat(response.acceptedRows()).isZero();
        assertThat(response.rejectedRows()).isEqualTo(2);
        assertThat(response.validationMessages()).anySatisfy(message -> assertThat(message).contains("INVALID_TIMESTAMP"));
        assertThat(response.validationMessages()).anySatisfy(message -> assertThat(message).contains("NON_FINITE_METRIC_VALUE"));

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(MlValidationReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getRejectedReasonsJson())
                .contains("INVALID_TIMESTAMP")
                .contains("NON_FINITE_METRIC_VALUE");
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
                true);
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile(
                "file",
                "dataset.csv",
                "text/csv",
                content.stripIndent().getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile jsonl(String content) {
        return new MockMultipartFile(
                "file",
                "dataset.jsonl",
                "application/x-ndjson",
                content.stripIndent().getBytes(StandardCharsets.UTF_8));
    }
}
