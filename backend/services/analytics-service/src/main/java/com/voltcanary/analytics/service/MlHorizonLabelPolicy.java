package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

final class MlHorizonLabelPolicy {

    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService;

    MlHorizonLabelPolicy(MaintenanceWindowProjectionService maintenanceWindowProjectionService) {
        this.maintenanceWindowProjectionService = maintenanceWindowProjectionService;
    }

    PlannedLabel labelFor(
        MlCanonicalMetricRow row,
        List<MaintenanceOutcomeReadModel> outcomes,
        Instant observedThroughForDevice,
        MlHorizonLabelGenerationJob job
    ) {
        if (row.getDeviceId() == null) {
            return null;
        }
        Instant predictionAt = row.getTimestamp();
        Instant horizonEnd = predictionAt.plus(job.getHorizonDays(), ChronoUnit.DAYS);
        List<MaintenanceWindowReadModel> maintenanceWindows =
            maintenanceWindowProjectionService.activeOverlapping(
                row.getDeviceId(),
                predictionAt,
                predictionAt.plusMillis(1)
            );
        if (maintenanceWindows.stream().anyMatch(MaintenanceWindowReadModel::isExcludeFromMl)) {
            return new PlannedLabel(
                row,
                MlLabelType.MAINTENANCE,
                List.of(),
                "maintenance exclusion",
                job.getHorizonDays()
            );
        }
        MaintenanceOutcomeReadModel selectedPositive = outcomes
            .stream()
            .filter(
                outcome ->
                    outcome.getObservedAt().isAfter(predictionAt) &&
                    !outcome.getObservedAt().isAfter(horizonEnd)
            )
            .filter(outcome -> isPositive(labelType(outcome, job.isIncludePreventiveMaintenance())))
            .findFirst()
            .orElse(null);
        if (selectedPositive != null) {
            return new PlannedLabel(
                row,
                labelType(selectedPositive, job.isIncludePreventiveMaintenance()),
                List.of(selectedPositive.getMaintenanceOutcomeId()),
                "maintenance outcome",
                job.getHorizonDays()
            );
        }
        MaintenanceOutcomeReadModel selectedFeedback = outcomes
            .stream()
            .filter(
                outcome ->
                    outcome.getObservedAt().isAfter(predictionAt) &&
                    !outcome.getObservedAt().isAfter(horizonEnd)
            )
            .filter(outcome -> labelType(outcome, job.isIncludePreventiveMaintenance()) != null)
            .findFirst()
            .orElse(null);
        if (selectedFeedback != null) {
            return new PlannedLabel(
                row,
                labelType(selectedFeedback, job.isIncludePreventiveMaintenance()),
                List.of(selectedFeedback.getMaintenanceOutcomeId()),
                "maintenance feedback outcome",
                job.getHorizonDays()
            );
        }
        if (observedThroughForDevice == null || horizonEnd.isAfter(observedThroughForDevice)) {
            return null;
        }
        return new PlannedLabel(
            row,
            MlLabelType.NORMAL,
            List.of(),
            "no failure outcome within observable horizon",
            job.getHorizonDays()
        );
    }

    MlLabelType labelType(
        MaintenanceOutcomeReadModel outcome,
        boolean includePreventiveMaintenance
    ) {
        return switch (outcome.getOutcomeType()) {
            case "CONFIRMED_FAILURE" -> MlLabelType.CONFIRMED_FAILURE;
            case "CORRECTIVE_MAINTENANCE" -> MlLabelType.CORRECTIVE_MAINTENANCE;
            case "PREVENTIVE_MAINTENANCE" -> includePreventiveMaintenance
                ? MlLabelType.PREVENTIVE_MAINTENANCE
                : null;
            case "INSPECTION_NORMAL" -> MlLabelType.INSPECTION_NORMAL;
            case "FALSE_POSITIVE" -> MlLabelType.FALSE_POSITIVE;
            case "SENSOR_FAULT" -> MlLabelType.SENSOR_FAULT;
            case "UNRESOLVED_ANOMALY" -> MlLabelType.UNRESOLVED_ANOMALY;
            default -> null;
        };
    }

    MlLabel toLabel(
        UUID accountId,
        UUID actorId,
        UUID datasetVersionId,
        String generationVersion,
        String lineageHash,
        Instant now,
        PlannedLabel planned
    ) {
        MlLabel label = new MlLabel();
        label.setId(UUID.randomUUID());
        label.setAccountId(accountId);
        label.setDatasetVersionId(datasetVersionId);
        label.setDeviceId(planned.row().getDeviceId());
        label.setExternalDeviceKey(planned.row().getExternalDeviceKey());
        label.setMetricName(planned.row().getMetricName());
        label.setLabelStartAt(planned.row().getTimestamp());
        label.setLabelEndAt(planned.row().getTimestamp().plusMillis(1));
        label.setLabel(planned.label());
        label.setSource(MlLabelSource.MAINTENANCE_OUTCOME);
        label.setReason(planned.reason());
        label.setHorizonDays(planned.horizonDays());
        label.setGenerationVersion(generationVersion);
        label.setSourceOutcomeIdsJson(outcomeIdsJson(planned.sourceOutcomeIds()));
        label.setLineageHash(lineageHash);
        label.setCreatedBy(actorId);
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        return label;
    }

    boolean isPositive(MlLabelType label) {
        return (
            label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE
        );
    }

    String outcomeIdsJson(List<UUID> ids) {
        return (
            "[" +
            ids
                .stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) +
            "]"
        );
    }

    String lineageHash(
        UUID datasetVersionId,
        String generationVersion,
        int horizonDays,
        List<PlannedLabel> labels
    ) {
        String payload = labels
            .stream()
            .map(
                label ->
                    label.row().getId() +
                    "|" +
                    label.label().name() +
                    "|" +
                    outcomeIdsJson(label.sourceOutcomeIds())
            )
            .sorted()
            .collect(
                Collectors.joining(
                    "\n",
                    datasetVersionId + "|" + generationVersion + "|" + horizonDays + "\n",
                    ""
                )
            );
        return hash(payload);
    }

    String hash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return (
                "sha256:" +
                HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash label lineage", exception);
        }
    }

    record PlannedLabel(
        MlCanonicalMetricRow row,
        MlLabelType label,
        List<UUID> sourceOutcomeIds,
        String reason,
        int horizonDays
    ) {}
}
