package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.voltcanary.analytics.config.MlFeatureGenerationProperties;
import com.voltcanary.analytics.domain.*;
import com.voltcanary.analytics.repository.*;
import com.voltcanary.analytics.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class MlFeatureGenerationBoundariesTest {

    private final MlDatasetVersionRepository versions = mock(MlDatasetVersionRepository.class);
    private final MlCanonicalMetricRowRepository canonical = mock(
        MlCanonicalMetricRowRepository.class
    );
    private final MlImportedFileRepository files = mock(MlImportedFileRepository.class);
    private final MlImportedMetricRowRepository imported = mock(
        MlImportedMetricRowRepository.class
    );
    private final MlLabelRepository labels = mock(MlLabelRepository.class);
    private final MlFeatureGenerationProperties limits = new MlFeatureGenerationProperties();
    private final MlFeatureSnapshotRepository snapshots = mock(MlFeatureSnapshotRepository.class);
    private final MlFeatureRowRepository rows = mock(MlFeatureRowRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MlFeatureSnapshotWrites writes = new MlFeatureSnapshotWrites(
        versions,
        snapshots,
        rows,
        entities,
        mock(MlFeatureSnapshotResponseMapper.class),
        metrics
    );

    @Test
    void rejectsOversizedCanonicalInputAfterABoundedRead() {
        var version = version(1);
        limits.setMaxInputRows(2);
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        when(
            canonical.findByDatasetVersionIdOrderByTimestampAsc(
                version.getId(),
                PageRequest.of(0, 3)
            )
        ).thenReturn(
            List.of(
                new MlCanonicalMetricRow(),
                new MlCanonicalMetricRow(),
                new MlCanonicalMetricRow()
            )
        );

        assertThatThrownBy(() ->
            reader().capture(version.getAccountId(), version.getId())
        ).isInstanceOfSatisfying(ResponseStatusException.class, failure ->
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        );
        verify(canonical).findByDatasetVersionIdOrderByTimestampAsc(
            version.getId(),
            PageRequest.of(0, 3)
        );
        verifyNoInteractions(files, imported);
    }

    @Test
    void appliesOneInputBudgetAcrossImportedFiles() {
        var version = version(1);
        version.setSourceType(MlDatasetSourceType.EXTERNAL_IMPORT);
        var first = new MlImportedFile();
        first.setId(UUID.randomUUID());
        var second = new MlImportedFile();
        second.setId(UUID.randomUUID());
        limits.setMaxInputRows(2);
        when(
            files.findByDatasetIdOrderByImportedAtDesc(eq(version.getDatasetId()), any())
        ).thenReturn(List.of(first, second));
        when(
            imported.findByImportedFileIdOrderBySourceRowNumber(first.getId(), PageRequest.of(0, 3))
        ).thenReturn(List.of(new MlImportedMetricRow(), new MlImportedMetricRow()));
        when(
            imported.findByImportedFileIdOrderBySourceRowNumber(
                second.getId(),
                PageRequest.of(0, 1)
            )
        ).thenReturn(List.of(new MlImportedMetricRow()));

        assertThatThrownBy(() -> reader().captureRows(version)).isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure ->
                assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        );
        verify(imported).findByImportedFileIdOrderBySourceRowNumber(
            second.getId(),
            PageRequest.of(0, 1)
        );
    }

    @Test
    void rejectsPublicationWhenTheDatasetChangedDuringComputation() {
        var captured = version(1);
        var current = version(2);
        current.setId(captured.getId());
        when(versions.findById(captured.getId())).thenReturn(Optional.of(current));

        assertThatThrownBy(() ->
            writes.save(inputs(captured), prepared(captured))
        ).isInstanceOfSatisfying(ResponseStatusException.class, failure ->
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );
        verifyNoInteractions(snapshots, rows, entities);
        assertThat(metrics.find("analytics.ml.feature.generation.completed").counter()).isNull();
    }

    @Test
    void countsSuccessOnlyAfterTheTransactionCommits() {
        var version = version(1);
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        var database = new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build();
        try {
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
            transactions.executeWithoutResult(status -> {
                writes.save(inputs(version), prepared(version));
                assertThat(
                    metrics.find("analytics.ml.feature.generation.completed").counter()
                ).isNull();
                status.setRollbackOnly();
            });
            assertThat(
                metrics.find("analytics.ml.feature.generation.completed").counter()
            ).isNull();
            transactions.executeWithoutResult(status -> {
                writes.save(inputs(version), prepared(version));
                assertThat(
                    metrics.find("analytics.ml.feature.generation.completed").counter()
                ).isNull();
            });
            assertThat(
                metrics.get("analytics.ml.feature.generation.completed").counter().count()
            ).isEqualTo(1);
            verify(entities, times(2)).lock(version, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        } finally {
            database.shutdown();
        }
    }

    private MlFeatureInputReader reader() {
        return new MlFeatureInputReader(versions, canonical, files, imported, labels, limits);
    }

    private MlDatasetVersion version(long revision) {
        var version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(UUID.randomUUID());
        version.setAccountId(UUID.randomUUID());
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(MlDatasetStatus.READY);
        version.setRowRevision(revision);
        return version;
    }

    private MlFeatureInputs inputs(MlDatasetVersion version) {
        return new MlFeatureInputs(version, List.of(), List.of(), List.of());
    }

    private MlFeatureSnapshotAssembler.Prepared prepared(MlDatasetVersion version) {
        var snapshot = new MlFeatureSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setAccountId(version.getAccountId());
        snapshot.setDatasetVersionId(version.getId());
        snapshot.setFeatureSetVersion("boundary-test");
        return new MlFeatureSnapshotAssembler.Prepared(snapshot, List.of());
    }
}
