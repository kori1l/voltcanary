package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MlDatasetLabelReadinessService {

    private final MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository;
    private final MaintenanceWindowReadModelRepository maintenanceWindowRepository;
    private final MlDatasetSupportService support;

    public MlDatasetLabelReadinessService(
            MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository,
            MaintenanceWindowReadModelRepository maintenanceWindowRepository,
            MlDatasetSupportService support) {
        this.maintenanceOutcomeRepository = maintenanceOutcomeRepository;
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.support = support;
    }

    public MlDatasetLabelReadinessDto labelReadiness(
            UUID accountId,
            MlModelFamily modelFamily,
            Integer horizonDays,
            Instant from,
            Instant to,
            MlDatasetValidationResult validation) {
        if (modelFamily != MlModelFamily.FAILURE_PREDICTION) {
            return null;
        }
        if (horizonDays == null || horizonDays < 1) {
            return new MlDatasetLabelReadinessDto(
                    horizonDays,
                    0,
                    0,
                    0,
                    validation.extractedRows().size(),
                    0,
                    0.0,
                    MlWorkbenchStatus.BLOCKED);
        }
        List<MlDatasetTelemetryRow> rows = validation.extractedRows();
        if (rows.isEmpty()) {
            return new MlDatasetLabelReadinessDto(
                    horizonDays,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    MlWorkbenchStatus.BLOCKED);
        }
        Map<UUID, Instant> observedThroughByDevice = new LinkedHashMap<>();
        for (MlDatasetTelemetryRow row : rows) {
            observedThroughByDevice.merge(
                    row.deviceId(),
                    row.timestamp(),
                    (left, right) -> left.isAfter(right) ? left : right);
        }
        Instant horizonEnd = to.plus(Duration.ofDays(horizonDays));
        Map<UUID, List<MaintenanceOutcomeReadModel>> outcomesByDevice = new LinkedHashMap<>();
        for (MaintenanceOutcomeReadModel outcome : maintenanceOutcomeRepository.findByAccountAndObservedRange(accountId, from, horizonEnd)) {
            outcomesByDevice.computeIfAbsent(outcome.getDeviceId(), ignored -> new ArrayList<>()).add(outcome);
        }
        Map<UUID, List<MaintenanceWindowReadModel>> windowsByDevice = new LinkedHashMap<>();
        for (MlDatasetTelemetryRow row : rows) {
            windowsByDevice.computeIfAbsent(row.deviceId(), deviceId ->
                    maintenanceWindowRepository.findActiveOverlapping(deviceId, from, to));
        }

        long positive = 0;
        long normal = 0;
        long unresolved = 0;
        long unobservable = 0;
        long maintenanceExcluded = 0;
        for (MlDatasetTelemetryRow row : rows) {
            if (support.isMlExcluded(row, windowsByDevice.getOrDefault(row.deviceId(), List.of()))) {
                maintenanceExcluded++;
                continue;
            }
            Instant rowHorizonEnd = row.timestamp().plus(Duration.ofDays(horizonDays));
            List<MaintenanceOutcomeReadModel> outcomes = outcomesByDevice.getOrDefault(row.deviceId(), List.of());
            MaintenanceOutcomeReadModel positiveOutcome = outcomes.stream()
                    .filter(outcome -> outcome.getObservedAt().isAfter(row.timestamp()) && !outcome.getObservedAt().isAfter(rowHorizonEnd))
                    .filter(outcome -> support.isPositive(support.labelType(outcome)))
                    .findFirst()
                    .orElse(null);
            if (positiveOutcome != null) {
                positive++;
                continue;
            }
            boolean unresolvedOutcome = outcomes.stream()
                    .filter(outcome -> outcome.getObservedAt().isAfter(row.timestamp()) && !outcome.getObservedAt().isAfter(rowHorizonEnd))
                    .map(support::labelType)
                    .anyMatch(label -> label == MlLabelType.UNRESOLVED_ANOMALY || label == MlLabelType.SENSOR_FAULT || label == MlLabelType.UNKNOWN);
            if (unresolvedOutcome) {
                unresolved++;
                continue;
            }
            Instant observedThrough = observedThroughByDevice.get(row.deviceId());
            if (observedThrough == null || rowHorizonEnd.isAfter(observedThrough)) {
                unobservable++;
                continue;
            }
            normal++;
        }
        long labeled = positive + normal + unresolved + maintenanceExcluded;
        double coverageRatio = rows.isEmpty() ? 0.0 : (double) labeled / rows.size();
        MlWorkbenchStatus status = positive == 0
                ? MlWorkbenchStatus.BLOCKED
                : coverageRatio < 0.8 || normal == 0
                        ? MlWorkbenchStatus.READY_WITH_WARNINGS
                        : MlWorkbenchStatus.READY;
        return new MlDatasetLabelReadinessDto(
                horizonDays,
                positive,
                normal,
                unresolved,
                unobservable,
                maintenanceExcluded,
                coverageRatio,
                status);
    }
}
