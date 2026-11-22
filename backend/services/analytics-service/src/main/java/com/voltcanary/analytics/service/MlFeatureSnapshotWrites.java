package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class MlFeatureSnapshotWrites {

    private final MlDatasetVersionRepository versions;
    private final MlFeatureSnapshotRepository snapshots;
    private final MlFeatureRowRepository rows;
    private final EntityManager entities;
    private final MlFeatureSnapshotResponseMapper mapper;
    private final MeterRegistry metrics;

    @Transactional
    public MlFeatureSnapshotResponse save(
        MlFeatureInputs captured,
        MlFeatureSnapshotAssembler.Prepared prepared
    ) {
        var current = versions
            .findById(captured.version().getId())
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Dataset changed during feature generation"
                )
            );
        if (
            current.getStatus() != MlDatasetStatus.READY ||
            !Objects.equals(current.getRowRevision(), captured.version().getRowRevision())
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Dataset changed during feature generation"
            );
        }
        // Lifecycle writes observed before this publication must also lose their stale revision.
        entities.lock(current, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        var snapshot = prepared.snapshot();
        snapshots.save(snapshot);
        if (!prepared.rows().isEmpty()) {
            rows.saveAll(prepared.rows());
        }
        Runnable committed = () -> {
            metrics
                .counter(
                    "analytics.ml.feature.generation.completed",
                    "source_type",
                    current.getSourceType().name(),
                    "feature_set_version",
                    snapshot.getFeatureSetVersion()
                )
                .increment();
            log.info(
                "mlFeatureSnapshotGenerated featureSnapshotId={} datasetVersionId={} accountId={} rowCount={} featureDataHash={}",
                snapshot.getId(),
                current.getId(),
                snapshot.getAccountId(),
                snapshot.getRowCount(),
                snapshot.getFeatureDataHash()
            );
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        committed.run();
                    }
                }
            );
        } else {
            committed.run();
        }
        return mapper.response(snapshot);
    }
}
