package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.MlRegistryProperties;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlModelQueryService {

    private final MlModelVersionRepository modelVersionRepository;
    private final MlRegistryProperties registryProperties;
    private final MlDemoModelSeeder demoModelSeeder;
    private final MlModelRegistryResponseMapper mapper;

    public MlModelQueryService(
            MlModelVersionRepository modelVersionRepository,
            MlRegistryProperties registryProperties,
            MlDemoModelSeeder demoModelSeeder,
            MlModelRegistryResponseMapper mapper) {
        this.modelVersionRepository = modelVersionRepository;
        this.registryProperties = registryProperties;
        this.demoModelSeeder = demoModelSeeder;
        this.mapper = mapper;
    }

    @Transactional
    public List<MlModelVersionDto> listModels(UUID accountId) {
        return listModels(accountId, "default");
    }

    @Transactional
    public List<MlModelVersionDto> listModels(UUID accountId, String statusFilter) {
        if (registryProperties.isDemoSeedingEnabled()) {
            demoModelSeeder.ensureDemoModels(accountId);
        }
        String filter = normalizedFilter(statusFilter);
        return modelVersionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .filter(model -> matchesModelFilter(model.getStatus(), filter))
                .map(mapper::modelResponse)
                .toList();
    }

    public MlModelVersionDto getModel(UUID accountId, UUID modelVersionId) {
        return mapper.modelResponse(findOwnedModel(accountId, modelVersionId));
    }

    MlModelVersion findOwnedModel(UUID accountId, UUID modelVersionId) {
        MlModelVersion model = modelVersionRepository.findById(modelVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML model version not found"));
        if (!accountId.equals(model.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML model version not found");
        }
        return model;
    }

    private boolean matchesModelFilter(MlModelStatus status, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "failed" -> status == MlModelStatus.REJECTED;
            case "archived" -> status == MlModelStatus.ARCHIVED;
            case "deleted" -> status == MlModelStatus.DELETED;
            default -> status != MlModelStatus.ARCHIVED && status != MlModelStatus.DELETED;
        };
    }

    private String normalizedFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "default";
        }
        return statusFilter.trim().toLowerCase(Locale.ROOT);
    }
}
