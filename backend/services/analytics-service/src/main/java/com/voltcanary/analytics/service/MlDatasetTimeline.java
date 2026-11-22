package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.dto.MlDatasetTimelineBucketDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MlDatasetTimeline {
    private final MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository;
    private final MaintenanceWindowReadModelRepository maintenanceWindowRepository;
    private final MlDatasetSupportService support;

    public List<MlDatasetTimelineBucketDto> timeline(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request,
            MlDatasetValidationResult validation) {
        Instant from = request.from();
        Instant to = request.to();
        if (!to.isAfter(from)) {
            return List.of();
        }
        List<UUID> selectedDeviceIds = support.normalizedDeviceIds(request.deviceIds());
        Map<UUID, List<MaintenanceWindowReadModel>> windowsByDevice = new LinkedHashMap<>();
        for (UUID deviceId : selectedDeviceIds) {
            List<MaintenanceWindowReadModel> windows = maintenanceWindowRepository.findActiveOverlapping(deviceId, from, to);
            windowsByDevice.put(deviceId, windows == null ? List.of() : windows);
        }
        Set<UUID> selectedDevices = new LinkedHashSet<>(selectedDeviceIds);
        List<MaintenanceOutcomeReadModel> outcomeRows = maintenanceOutcomeRepository.findByAccountAndObservedRange(accountId, from, to);
        List<MaintenanceOutcomeReadModel> outcomes = (outcomeRows == null ? List.<MaintenanceOutcomeReadModel>of() : outcomeRows).stream()
                .filter(outcome -> selectedDevices.contains(outcome.getDeviceId()))
                .toList();
        int bucketCount = 12;
        long totalMillis = Math.max(1, Duration.between(from, to).toMillis());
        long bucketMillis = Math.max(1, totalMillis / bucketCount);
        List<MlDatasetTimelineBucketDto> buckets = new ArrayList<>();
        for (int index = 0; index < bucketCount; index++) {
            Instant bucketStart = from.plusMillis(bucketMillis * index);
            Instant bucketEnd = index == bucketCount - 1 ? to : from.plusMillis(bucketMillis * (index + 1));
            long samples = validation.extractedRows().stream()
                    .filter(row -> !row.timestamp().isBefore(bucketStart) && row.timestamp().isBefore(bucketEnd))
                    .count();
            List<String> events = timelineEvents(bucketStart, bucketEnd, index == bucketCount - 1, windowsByDevice, outcomes);
            buckets.add(new MlDatasetTimelineBucketDto(
                    bucketStart,
                    bucketEnd,
                    samples,
                    samples == 0 ? 0.0 : 1.0,
                    samples == 0 ? MlWorkbenchStatus.NO_DATA : MlWorkbenchStatus.READY,
                    events));
        }
        return buckets;
    }

    private List<String> timelineEvents(
            Instant bucketStart,
            Instant bucketEnd,
            boolean lastBucket,
            Map<UUID, List<MaintenanceWindowReadModel>> windowsByDevice,
            List<MaintenanceOutcomeReadModel> outcomes) {
        List<String> events = new ArrayList<>();
        for (List<MaintenanceWindowReadModel> windows : windowsByDevice.values()) {
            for (MaintenanceWindowReadModel window : windows) {
                if (windowOverlaps(bucketStart, bucketEnd, window)) {
                    events.add(window.isExcludeFromMl() ? "MAINTENANCE_EXCLUDED" : "MAINTENANCE_OVERLAP");
                }
            }
        }
        for (MaintenanceOutcomeReadModel outcome : outcomes) {
            Instant observedAt = outcome.getObservedAt();
            boolean inBucket = lastBucket
                    ? !observedAt.isBefore(bucketStart) && !observedAt.isAfter(bucketEnd)
                    : !observedAt.isBefore(bucketStart) && observedAt.isBefore(bucketEnd);
            if (inBucket) {
                events.add(support.labelType(outcome).name());
            }
        }
        return support.distinct(events);
    }

    private boolean windowOverlaps(Instant bucketStart, Instant bucketEnd, MaintenanceWindowReadModel window) {
        return window.getStartAt().isBefore(bucketEnd) && window.getEndAt().isAfter(bucketStart);
    }


}
