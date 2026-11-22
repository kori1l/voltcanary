package com.voltcanary.analytics;

import com.voltcanary.analytics.dto.MlCsvSchemaMapping;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlExternalDatasetImportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false", "app.kafka.publish-enabled=false",
        "app.analytics.jobs.worker-enabled=false", "app.maintenance.reconciliation.enabled=false",
        "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ml-external-import-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.open-in-view=false", "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"
})
class MlExternalImportBoundaryTest {
    @Autowired private MlExternalDatasetImportService imports;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean private AnalyticsAccessService access;
    @MockitoSpyBean private MlImportedMetricRowRepository rows;

    @Test
    void ownershipLookupIsOutsideTransactionAndSavingFailureRollsBackWholeImport() {
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        when(access.accountIdForDevice(device, null)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return account;
        });
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("storage unavailable");
        }).when(rows).saveAll(any());
        assertThatThrownBy(() -> imports.importCsv(account, UUID.randomUUID(), "rollback-test", null, file(device), mapping()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from ml_datasets where account_id=?", Integer.class, account)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ml_imported_files where account_id=?", Integer.class, account)).isZero();
    }

    @Test
    void successfulImportCommitsDatasetFileRowsMappingsVersionAndReport() {
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        when(access.accountIdForDevice(device, null)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return account;
        });
        var result = imports.importCsv(account, UUID.randomUUID(), "successful-import", null, file(device), mapping());
        assertThat(result.acceptedRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ml_datasets where id=?", Integer.class, result.datasetId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ml_imported_files where id=?", Integer.class, result.importedFileId())).isEqualTo(1);
        assertThat(rows.findAll()).anySatisfy(row -> {
            assertThat(row.getImportedFileId()).isEqualTo(result.importedFileId());
            assertThat(row.getMatchedDeviceId()).isEqualTo(device);
        });
        assertThat(jdbc.queryForObject("select count(*) from ml_dataset_versions where id=?", Integer.class, result.datasetVersionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ml_validation_reports where id=?", Integer.class, result.validationReportId())).isEqualTo(1);
    }

    @Test
    void transactionalCallerIsRejectedBeforeReadingOrSaving() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status ->
                imports.importCsv(UUID.randomUUID(), UUID.randomUUID(), "forbidden", null, file(UUID.randomUUID()), mapping())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private MockMultipartFile file(UUID device) {
        String body = "ts,device,metric,value,existingDeviceId\n2026-07-01T00:00:00Z,washer,activePowerW,1," + device + "\n";
        return new MockMultipartFile("file", "dataset.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private MlCsvSchemaMapping mapping() {
        return new MlCsvSchemaMapping("ts", "device", "metric", "value", null, null,
                "existingDeviceId", null, List.of(), "boundary-v1", true);
    }
}
