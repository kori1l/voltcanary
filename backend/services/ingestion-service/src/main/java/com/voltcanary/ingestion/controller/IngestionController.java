package com.voltcanary.ingestion.controller;

import com.voltcanary.ingestion.dto.BatchTelemetryIngestionRequest;
import com.voltcanary.ingestion.dto.IngestionAcceptedResponse;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.ingress.IngressOrchestrator;
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

    private final IngressOrchestrator ingressOrchestrator;

    public IngestionController(IngressOrchestrator ingressOrchestrator) {
        this.ingressOrchestrator = ingressOrchestrator;
    }

    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @Valid @RequestBody TelemetryIngestionRequest request) {
        return ingressOrchestrator.ingestHttp(
                request,
                bearerToken(authorization),
                correlationId,
                clientIp(forwardedFor),
                false);
    }

    @PostMapping("/imports/readings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingestImport(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @Valid @RequestBody TelemetryIngestionRequest request) {
        return ingressOrchestrator.ingestHttp(
                request,
                bearerToken(authorization),
                correlationId,
                clientIp(forwardedFor),
                true);
    }

    @PostMapping("/readings/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingestBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @Valid @RequestBody BatchTelemetryIngestionRequest request) {
        return ingressOrchestrator.ingestHttpBatch(
                request.readings(),
                bearerToken(authorization),
                correlationId,
                clientIp(forwardedFor),
                false);
    }

    @PostMapping("/imports/readings/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse ingestImportBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @Valid @RequestBody BatchTelemetryIngestionRequest request) {
        return ingressOrchestrator.ingestHttpBatch(
                request.readings(),
                bearerToken(authorization),
                correlationId,
                clientIp(forwardedFor),
                true);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length());
    }

    private String clientIp(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        return forwardedFor.split(",", 2)[0].trim();
    }
}
