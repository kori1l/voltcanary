package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.dto.MlDatasetDetailDto;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.repository.MlDatasetLineageRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MlDatasetQueryService {

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlValidationReportRepository validationReportRepository;
    private final MlDatasetLineageRepository lineageRepository;
    private final MlDatasetResponseMapper mapper;

    @Transactional(readOnly = true)
    public List<MlDatasetSummaryDto> list(UUID accountId) {
        return list(accountId, "default");
    }

    @Transactional(readOnly = true)
    public List<MlDatasetSummaryDto> list(UUID accountId, String statusFilter) {
        String filter = normalizedFilter(statusFilter);
        var datasets = datasetRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
        boolean latestOnly = List.of("default", "active", "current").contains(filter);
        var selectedVersions = latestOnly
                ? datasetVersionRepository.findLatestVisibleByAccountId(accountId, List.of(MlDatasetStatus.ARCHIVED, MlDatasetStatus.DELETED))
                : switch (filter) {
                    case "archived" -> datasetVersionRepository.findByAccountIdAndStatusOrderByCreatedAtDesc(accountId, MlDatasetStatus.ARCHIVED);
                    case "deleted" -> datasetVersionRepository.findByAccountIdAndStatusOrderByCreatedAtDesc(accountId, MlDatasetStatus.DELETED);
                    case "failed" -> datasetVersionRepository.findByAccountIdAndStatusOrderByCreatedAtDesc(accountId, MlDatasetStatus.FAILED);
                    default -> datasetVersionRepository.findByAccountIdOrderByVersionDesc(accountId);
                };
        var versions = selectedVersions.stream()
                .collect(java.util.stream.Collectors.groupingBy(MlDatasetVersion::getDatasetId));
        return datasets.stream().flatMap(dataset -> {
            var selected = versions.getOrDefault(dataset.getId(), List.of()).stream()
                    .filter(version -> matchesFilter(version.getStatus(), filter));
            if (latestOnly) selected = selected.limit(1);
            return selected.map(version -> mapper.summary(dataset, version));
        }).toList();
    }

    @Transactional(readOnly = true)
    public MlDatasetDetailDto detail(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion version = datasetVersionRepository.findById(datasetVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found"));
        if (!accountId.equals(version.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        MlDataset dataset = datasetRepository.findById(version.getDatasetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset not found"));
        return new MlDatasetDetailDto(
                mapper.summary(dataset, version),
                validationReportRepository.findByDatasetVersionIdOrderByCreatedAtDesc(version.getId()).stream()
                        .map(mapper::validationReport)
                        .toList(),
                lineageRepository.findByDatasetVersionId(version.getId()).stream()
                        .map(mapper::lineage)
                        .toList());
    }

    private boolean matchesFilter(MlDatasetStatus status, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "failed" -> status == MlDatasetStatus.FAILED;
            case "archived" -> status == MlDatasetStatus.ARCHIVED;
            case "deleted" -> status == MlDatasetStatus.DELETED;
            default -> status != MlDatasetStatus.ARCHIVED && status != MlDatasetStatus.DELETED;
        };
    }

    private String normalizedFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "default";
        }
        return statusFilter.trim().toLowerCase(Locale.ROOT);
    }
}
