package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RecalculateResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.service.AnalyticsRecalculationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analytics/devices/{deviceId}")
public class AnalyticsController {

    private final AnalyticsRecalculationService recalculationService;

    public AnalyticsController(AnalyticsRecalculationService recalculationService) {
        this.recalculationService = recalculationService;
    }

    @GetMapping("/baseline")
    public BaselineResponse baseline(@PathVariable("deviceId") UUID deviceId) {
        return recalculationService.baseline(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No baseline for device"));
    }

    @GetMapping("/features")
    public List<Object> features(@PathVariable("deviceId") UUID deviceId) {
        return List.of();
    }

    @GetMapping("/anomalies")
    public List<AnomalyResponse> anomalies(@PathVariable("deviceId") UUID deviceId) {
        return recalculationService.anomalies(deviceId);
    }

    @GetMapping("/risk")
    public RiskResponse risk(@PathVariable("deviceId") UUID deviceId) {
        return recalculationService.risk(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No risk score for device"));
    }

    @GetMapping("/recommendations")
    public List<RecommendationResponse> recommendations(@PathVariable("deviceId") UUID deviceId) {
        return recalculationService.recommendations(deviceId);
    }

    @GetMapping("/predictions/latest")
    public PredictionResponse latestPrediction(@PathVariable("deviceId") UUID deviceId) {
        return recalculationService.latestPrediction(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No prediction for device"));
    }

    @PostMapping("/recalculate")
    public RecalculateResponse recalculate(@PathVariable("deviceId") UUID deviceId) {
        AnalyticsRecalculationService.RecalculationResult result = recalculationService.recalculate(deviceId);
        return new RecalculateResponse(
                "completed",
                deviceId,
                result.baseline().getId(),
                result.anomalies().size(),
                result.riskScore().getScore(),
                result.riskScore().getLevel(),
                result.recommendations().size());
    }
}
