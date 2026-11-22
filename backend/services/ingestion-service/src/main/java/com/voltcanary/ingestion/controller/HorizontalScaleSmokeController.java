package com.voltcanary.ingestion.controller;

import com.voltcanary.ingestion.service.HorizontalScaleSmokeOperations;
import com.voltcanary.ingestion.service.HorizontalScaleSmokeOperations.SmokeSourceRuntimeConfigRequest;
import com.voltcanary.ingestion.service.HorizontalScaleSmokeOperations.SourceReadinessRequest;
import com.voltcanary.ingestion.service.HorizontalScaleSmokeOperations.SourceReadinessResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ingestion/horizontal-scale-smoke")
@ConditionalOnProperty(
    prefix = "app.ingestion.horizontal-scale-smoke",
    name = "enabled",
    havingValue = "true"
)
public class HorizontalScaleSmokeController {

    private final HorizontalScaleSmokeOperations useCase;

    public HorizontalScaleSmokeController(HorizontalScaleSmokeOperations useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/source-runtime-configs")
    public Map<String, Object> publishSourceRuntimeConfig(
        @RequestBody SmokeSourceRuntimeConfigRequest request
    ) {
        return useCase.publishSourceRuntimeConfig(request);
    }

    @GetMapping("/sources/{dataSourceId}")
    public Map<String, Object> sourceState(@PathVariable("dataSourceId") UUID dataSourceId) {
        return useCase.sourceState(dataSourceId);
    }

    @PostMapping("/sources/readiness")
    public SourceReadinessResponse sourceReadiness(@RequestBody SourceReadinessRequest request) {
        return useCase.sourceReadiness(request);
    }
}
