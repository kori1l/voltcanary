package com.voltcanary.ingestion.controller;

import com.voltcanary.ingestion.dto.BatchTelemetryIngestionRequest;
import com.voltcanary.ingestion.dto.IngestionAcceptedResponse;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    public IngestionAcceptedResponse ingest(@Valid @RequestBody TelemetryIngestionRequest request) {
        ingestionService.ingest(request);
        return new IngestionAcceptedResponse("accepted", 1);
    }

    @PostMapping("/readings/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingestBatch(@Valid @RequestBody BatchTelemetryIngestionRequest request) {
        ingestionService.ingestBatch(request.readings());
        return new IngestionAcceptedResponse("accepted", request.readings().size());
    }
}
