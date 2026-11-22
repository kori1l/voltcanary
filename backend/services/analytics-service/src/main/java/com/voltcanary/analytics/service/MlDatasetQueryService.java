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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
        return list(accountId, statusFilter, 0, 100);
    }

    @Transactional(readOnly = true)
    public List<MlDatasetSummaryDto> list(UUID accountId, String statusFilter, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "page must be non-negative and size must be between 1 and 100"
            );
        }
        String filter = normalizedFilter(statusFilter);
        boolean latestOnly = List.of("default", "active", "current").contains(filter);
        List<MlDatasetStatus> statuses = switch (filter) {
            case "default", "active", "current" -> List.of(
                MlDatasetStatus.DRAFT,
                MlDatasetStatus.VALIDATING,
                MlDatasetStatus.READY,
                MlDatasetStatus.FAILED
            );
            case "archived" -> List.of(MlDatasetStatus.ARCHIVED);
            case "deleted" -> List.of(MlDatasetStatus.DELETED);
            case "failed" -> List.of(MlDatasetStatus.FAILED);
            case "all" -> List.of(MlDatasetStatus.values());
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported dataset status filter"
            );
        };
        var versions = datasetVersionRepository
            .findInventory(accountId, statuses, latestOnly, PageRequest.of(page, size))
            .getContent();
        var datasets = datasetRepository
            .findAllById(versions.stream().map(MlDatasetVersion::getDatasetId).distinct().toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(MlDataset::getId, dataset -> dataset));
        return versions
            .stream()
            .map(version -> {
                MlDataset dataset = datasets.get(version.getDatasetId());
                if (dataset == null || !accountId.equals(dataset.getAccountId())) {
                    throw new IllegalStateException("Dataset inventory changed while reading");
                }
                return mapper.summary(dataset, version);
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public MlDatasetDetailDto detail(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion version = datasetVersionRepository
            .findById(datasetVersionId)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found")
            );
        if (!accountId.equals(version.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        MlDataset dataset = datasetRepository
            .findById(version.getDatasetId())
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset not found")
            );
        return new MlDatasetDetailDto(
            mapper.summary(dataset, version),
            validationReportRepository
                .findByDatasetVersionIdOrderByCreatedAtDesc(version.getId())
                .stream()
                .map(mapper::validationReport)
                .toList(),
            lineageRepository
                .findByDatasetVersionId(version.getId())
                .stream()
                .map(mapper::lineage)
                .toList()
        );
    }

    private String normalizedFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "default";
        }
        return statusFilter.trim().toLowerCase(Locale.ROOT);
    }
}
