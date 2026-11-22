package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResult;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.service.MlHorizonLabelPolicy.PlannedLabel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class MlHorizonLabelMaterialization {

    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MaintenanceOutcomeReadModelRepository outcomeRepository;
    private final MlLabelRepository labelRepository;
    private final MlHorizonLabelPolicy labelPolicy;

    MlHorizonLabelMaterialization(
        MlCanonicalMetricRowRepository rows,
        MaintenanceOutcomeReadModelRepository outcomes,
        MlLabelRepository labels,
        MlHorizonLabelPolicy policy
    ) {
        canonicalMetricRowRepository = rows;
        outcomeRepository = outcomes;
        labelRepository = labels;
        labelPolicy = policy;
    }

    MlHorizonLabelGenerationResult generateLabels(
        MlHorizonLabelGenerationJob job,
        MlDatasetVersion datasetVersion
    ) {
        List<MlCanonicalMetricRow> rows =
            canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(
                datasetVersion.getId()
            );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Dataset version has no canonical rows"
            );
        }
        Instant first = rows.getFirst().getTimestamp();
        Instant observedThrough = rows
            .stream()
            .map(MlCanonicalMetricRow::getTimestamp)
            .max(Comparator.naturalOrder())
            .orElse(first);
        Map<UUID, Instant> observedThroughByDevice = rows
            .stream()
            .filter(row -> row.getDeviceId() != null)
            .collect(
                Collectors.toMap(
                    MlCanonicalMetricRow::getDeviceId,
                    MlCanonicalMetricRow::getTimestamp,
                    (left, right) -> left.isAfter(right) ? left : right
                )
            );
        Instant horizonEnd = observedThrough.plus(job.getHorizonDays(), ChronoUnit.DAYS);
        Map<UUID, List<MaintenanceOutcomeReadModel>> outcomesByDevice = outcomeRepository
            .findByAccountAndObservedRange(job.getAccountId(), first, horizonEnd)
            .stream()
            .filter(outcome -> outcome.getDeviceId() != null)
            .collect(Collectors.groupingBy(MaintenanceOutcomeReadModel::getDeviceId));

        List<PlannedLabel> planned = rows
            .stream()
            .map(row ->
                labelPolicy.labelFor(
                    row,
                    outcomesByDevice.getOrDefault(row.getDeviceId(), List.of()),
                    observedThroughByDevice.get(row.getDeviceId()),
                    job
                )
            )
            .filter(item -> item != null)
            .toList();
        String lineageHash = labelPolicy.lineageHash(
            datasetVersion.getId(),
            job.getGenerationVersion(),
            job.getHorizonDays(),
            planned
        );

        labelRepository.deleteGeneratedOutcomeLabels(
            job.getAccountId(),
            datasetVersion.getId(),
            job.getHorizonDays()
        );
        Instant now = Instant.now();
        List<MlLabel> labels = planned
            .stream()
            .map(item ->
                labelPolicy.toLabel(
                    job.getAccountId(),
                    job.getSubmittedBy(),
                    datasetVersion.getId(),
                    job.getGenerationVersion(),
                    lineageHash,
                    now,
                    item
                )
            )
            .toList();
        labelRepository.saveAll(labels);

        int positives = (int) labels
            .stream()
            .filter(label -> labelPolicy.isPositive(label.getLabel()))
            .count();
        int normals = (int) labels
            .stream()
            .filter(label -> label.getLabel() == MlLabelType.NORMAL)
            .count();
        int maintenance = (int) labels
            .stream()
            .filter(label -> label.getLabel() == MlLabelType.MAINTENANCE)
            .count();
        int unresolved = (int) labels
            .stream()
            .filter(label -> label.getLabel() == MlLabelType.UNRESOLVED_ANOMALY)
            .count();
        int unobservable = rows.size() - labels.size();
        return new MlHorizonLabelGenerationResult(
            lineageHash,
            labels.size(),
            positives,
            normals,
            maintenance,
            unresolved,
            unobservable
        );
    }
}
