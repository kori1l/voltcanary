package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationRequest;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResponse;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlHorizonLabelGenerationService {

    private static final String DEFAULT_GENERATION_VERSION = "failure-outcome-v1";

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MaintenanceOutcomeReadModelRepository outcomeRepository;
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService;
    private final MlLabelRepository labelRepository;

    public MlHorizonLabelGenerationService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlCanonicalMetricRowRepository canonicalMetricRowRepository,
            MaintenanceOutcomeReadModelRepository outcomeRepository,
            MaintenanceWindowProjectionService maintenanceWindowProjectionService,
            MlLabelRepository labelRepository) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.canonicalMetricRowRepository = canonicalMetricRowRepository;
        this.outcomeRepository = outcomeRepository;
        this.maintenanceWindowProjectionService = maintenanceWindowProjectionService;
        this.labelRepository = labelRepository;
    }

    @Transactional
    public MlHorizonLabelGenerationResponse generate(UUID accountId, UUID actorId, MlHorizonLabelGenerationRequest request) {
        if (request.horizonDays() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "horizonDays must be positive");
        }
        MlDatasetVersion datasetVersion = datasetVersionRepository.findById(request.datasetVersionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found"));
        if (!accountId.equals(datasetVersion.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found");
        }
        List<MlCanonicalMetricRow> rows = canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersion.getId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset version has no canonical rows");
        }
        Instant first = rows.getFirst().getTimestamp();
        Instant observedThrough = rows.stream()
                .map(MlCanonicalMetricRow::getTimestamp)
                .max(Comparator.naturalOrder())
                .orElse(first);
        Map<UUID, Instant> observedThroughByDevice = rows.stream()
                .filter(row -> row.getDeviceId() != null)
                .collect(Collectors.toMap(
                        MlCanonicalMetricRow::getDeviceId,
                        MlCanonicalMetricRow::getTimestamp,
                        (left, right) -> left.isAfter(right) ? left : right));
        Instant horizonEnd = observedThrough.plus(request.horizonDays(), ChronoUnit.DAYS);
        Map<UUID, List<MaintenanceOutcomeReadModel>> outcomesByDevice = outcomeRepository
                .findByAccountAndObservedRange(accountId, first, horizonEnd)
                .stream()
                .filter(outcome -> outcome.getDeviceId() != null)
                .collect(Collectors.groupingBy(MaintenanceOutcomeReadModel::getDeviceId));

        String generationVersion = normalizedGenerationVersion(request.generationVersion());
        List<PlannedLabel> planned = rows.stream()
                .map(row -> labelFor(
                        row,
                        outcomesByDevice.getOrDefault(row.getDeviceId(), List.of()),
                        observedThroughByDevice.get(row.getDeviceId()),
                        request))
                .filter(item -> item != null)
                .toList();
        String lineageHash = lineageHash(datasetVersion.getId(), generationVersion, request.horizonDays(), planned);

        labelRepository.deleteGeneratedOutcomeLabels(accountId, datasetVersion.getId(), request.horizonDays());
        Instant now = Instant.now();
        List<MlLabel> labels = planned.stream()
                .map(item -> toLabel(accountId, actorId, datasetVersion.getId(), generationVersion, lineageHash, now, item))
                .toList();
        labelRepository.saveAll(labels);

        int positives = (int) labels.stream().filter(label -> isPositive(label.getLabel())).count();
        int normals = (int) labels.stream().filter(label -> label.getLabel() == MlLabelType.NORMAL).count();
        int maintenance = (int) labels.stream().filter(label -> label.getLabel() == MlLabelType.MAINTENANCE).count();
        int unresolved = (int) labels.stream().filter(label -> label.getLabel() == MlLabelType.UNRESOLVED_ANOMALY).count();
        int unobservable = rows.size() - labels.size();
        return new MlHorizonLabelGenerationResponse(
                datasetVersion.getId(),
                request.horizonDays(),
                generationVersion,
                lineageHash,
                labels.size(),
                positives,
                normals,
                maintenance,
                unresolved,
                unobservable);
    }

    private PlannedLabel labelFor(
            MlCanonicalMetricRow row,
            List<MaintenanceOutcomeReadModel> outcomes,
            Instant observedThroughForDevice,
            MlHorizonLabelGenerationRequest request) {
        if (row.getDeviceId() == null) {
            return null;
        }
        Instant predictionAt = row.getTimestamp();
        Instant horizonEnd = predictionAt.plus(request.horizonDays(), ChronoUnit.DAYS);
        List<MaintenanceWindowReadModel> maintenanceWindows = maintenanceWindowProjectionService
                .activeOverlapping(row.getDeviceId(), predictionAt, predictionAt.plusMillis(1));
        if (maintenanceWindows.stream().anyMatch(MaintenanceWindowReadModel::isExcludeFromMl)) {
            return new PlannedLabel(row, MlLabelType.MAINTENANCE, List.of(), "maintenance exclusion", request.horizonDays());
        }
        MaintenanceOutcomeReadModel selectedPositive = outcomes.stream()
                .filter(outcome -> outcome.getObservedAt().isAfter(predictionAt) && !outcome.getObservedAt().isAfter(horizonEnd))
                .filter(outcome -> isPositive(labelType(outcome, request.includePreventiveMaintenance())))
                .findFirst()
                .orElse(null);
        if (selectedPositive != null) {
            return new PlannedLabel(
                    row,
                    labelType(selectedPositive, request.includePreventiveMaintenance()),
                    List.of(selectedPositive.getMaintenanceOutcomeId()),
                    "maintenance outcome",
                    request.horizonDays());
        }
        MaintenanceOutcomeReadModel selectedFeedback = outcomes.stream()
                .filter(outcome -> outcome.getObservedAt().isAfter(predictionAt) && !outcome.getObservedAt().isAfter(horizonEnd))
                .filter(outcome -> labelType(outcome, request.includePreventiveMaintenance()) != null)
                .findFirst()
                .orElse(null);
        if (selectedFeedback != null) {
            return new PlannedLabel(
                    row,
                    labelType(selectedFeedback, request.includePreventiveMaintenance()),
                    List.of(selectedFeedback.getMaintenanceOutcomeId()),
                    "maintenance feedback outcome",
                    request.horizonDays());
        }
        if (observedThroughForDevice == null || horizonEnd.isAfter(observedThroughForDevice)) {
            return null;
        }
        return new PlannedLabel(row, MlLabelType.NORMAL, List.of(), "no failure outcome within observable horizon", request.horizonDays());
    }

    private MlLabelType labelType(MaintenanceOutcomeReadModel outcome, boolean includePreventiveMaintenance) {
        return switch (outcome.getOutcomeType()) {
            case "CONFIRMED_FAILURE" -> MlLabelType.CONFIRMED_FAILURE;
            case "CORRECTIVE_MAINTENANCE" -> MlLabelType.CORRECTIVE_MAINTENANCE;
            case "PREVENTIVE_MAINTENANCE" -> includePreventiveMaintenance ? MlLabelType.PREVENTIVE_MAINTENANCE : null;
            case "INSPECTION_NORMAL" -> MlLabelType.INSPECTION_NORMAL;
            case "FALSE_POSITIVE" -> MlLabelType.FALSE_POSITIVE;
            case "SENSOR_FAULT" -> MlLabelType.SENSOR_FAULT;
            case "UNRESOLVED_ANOMALY" -> MlLabelType.UNRESOLVED_ANOMALY;
            default -> null;
        };
    }

    private MlLabel toLabel(
            UUID accountId,
            UUID actorId,
            UUID datasetVersionId,
            String generationVersion,
            String lineageHash,
            Instant now,
            PlannedLabel planned) {
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

    private boolean isPositive(MlLabelType label) {
        return label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE;
    }

    private String normalizedGenerationVersion(String value) {
        return value == null || value.isBlank() ? DEFAULT_GENERATION_VERSION : value.trim();
    }

    private String outcomeIdsJson(List<UUID> ids) {
        return "[" + ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    private String lineageHash(UUID datasetVersionId, String generationVersion, int horizonDays, List<PlannedLabel> labels) {
        String payload = labels.stream()
                .map(label -> label.row().getId()
                        + "|"
                        + label.label().name()
                        + "|"
                        + outcomeIdsJson(label.sourceOutcomeIds()))
                .sorted()
                .collect(Collectors.joining("\n", datasetVersionId + "|" + generationVersion + "|" + horizonDays + "\n", ""));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash label lineage", exception);
        }
    }

    private record PlannedLabel(
            MlCanonicalMetricRow row,
            MlLabelType label,
            List<UUID> sourceOutcomeIds,
            String reason,
            int horizonDays) {
    }
}
