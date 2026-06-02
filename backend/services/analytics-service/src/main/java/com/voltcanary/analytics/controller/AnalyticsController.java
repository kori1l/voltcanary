package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.AnalyticsJobResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.FeatureSetResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RecalculateResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.AnalyticsJobService;
import com.voltcanary.analytics.service.AnalyticsRecalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analytics/devices/{deviceId}")
@Tag(name = "Analytics", description = "User-facing analytics results and recalculation jobs.")
public class AnalyticsController {

    private final AnalyticsRecalculationService recalculationService;
    private final AnalyticsJobService jobService;
    private final AnalyticsAccessService accessService;

    public AnalyticsController(
            AnalyticsRecalculationService recalculationService,
            AnalyticsJobService jobService,
            AnalyticsAccessService accessService) {
        this.recalculationService = recalculationService;
        this.jobService = jobService;
        this.accessService = accessService;
    }

    @GetMapping("/baseline")
    @Operation(summary = "Get latest baseline", description = "Returns the latest calculated baseline for a device metric.")
    public BaselineResponse baseline(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return recalculationService.baseline(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No baseline for device"));
    }

    @GetMapping("/features")
    @Operation(summary = "List feature windows", description = "Returns recent calculated feature windows for the device.")
    public List<FeatureSetResponse> features(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return recalculationService.features(deviceId);
    }

    @GetMapping("/anomalies")
    @Operation(summary = "List active anomalies", description = "Returns active anomaly detections for the device.")
    public List<AnomalyResponse> anomalies(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return recalculationService.anomalies(deviceId);
    }

    @GetMapping("/risk")
    @Operation(summary = "Get latest risk score", description = "Returns the latest risk score and explanation snippets.")
    public RiskResponse risk(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return recalculationService.risk(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No risk score for device"));
    }

    @GetMapping("/recommendations")
    @Operation(summary = "List active recommendations", description = "Returns active recommendations generated from analytics results.")
    public List<RecommendationResponse> recommendations(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return recalculationService.recommendations(deviceId);
    }

    @GetMapping("/predictions/latest")
    @Operation(summary = "Get latest prediction", description = "Returns the latest ML or rule-fallback prediction for the device.")
    public PredictionResponse latestPrediction(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return recalculationService.latestPrediction(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No prediction for device"));
    }

    @PostMapping("/recalculate")
    @Operation(summary = "Enqueue recalculation", description = "Creates or reuses an analytics recalculation job for the device.")
    public RecalculateResponse recalculate(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        AnalyticsJobResponse job = jobService.enqueueRecalculation(deviceId);
        return new RecalculateResponse(
                job.status().toLowerCase(),
                deviceId,
                job.id(),
                null,
                0,
                0,
                "",
                0);
    }

    @GetMapping("/jobs")
    @Operation(summary = "List analytics jobs", description = "Returns recent recalculation jobs for the device.")
    public List<AnalyticsJobResponse> jobs(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        return jobService.list(deviceId);
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get analytics job", description = "Returns status and error details for one recalculation job.")
    public AnalyticsJobResponse job(
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("jobId") UUID jobId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        accessService.requireReadAccess(deviceId, userId, role, correlationId);
        AnalyticsJobResponse job = jobService.find(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analytics job not found"));
        if (!deviceId.equals(job.deviceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analytics job not found");
        }
        return job;
    }
}
