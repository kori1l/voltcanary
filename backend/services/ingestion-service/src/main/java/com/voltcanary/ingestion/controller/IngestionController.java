package com.voltcanary.ingestion.controller;

import com.voltcanary.ingestion.dto.BatchTelemetryIngestionRequest;
import com.voltcanary.ingestion.dto.IngestionAcceptedResponse;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody TelemetryIngestionRequest request) {
        ingestionService.ingest(request, bearerToken(authorization), correlationId);
        return new IngestionAcceptedResponse("accepted", 1);
    }

    @PostMapping("/readings/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingestBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody BatchTelemetryIngestionRequest request) {
        ingestionService.ingestBatch(request.readings(), bearerToken(authorization), correlationId);
        return new IngestionAcceptedResponse("accepted", request.readings().size());
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length());
    }
}
