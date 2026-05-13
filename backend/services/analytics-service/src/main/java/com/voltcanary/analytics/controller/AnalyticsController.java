package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.baseline.BaselineCalculator;
import com.voltcanary.analytics.dto.RecalculateResponse;
import com.voltcanary.analytics.risk.RiskScoreCalculator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics/devices/{deviceId}")
public class AnalyticsController {

    private final BaselineCalculator baselineCalculator;
    private final RiskScoreCalculator riskScoreCalculator;

    public AnalyticsController(BaselineCalculator baselineCalculator, RiskScoreCalculator riskScoreCalculator) {
        this.baselineCalculator = baselineCalculator;
        this.riskScoreCalculator = riskScoreCalculator;
    }

    @GetMapping("/baseline")
    public Map<String, Object> baseline(@PathVariable UUID deviceId) {
        return Map.of("deviceId", deviceId, "baseline", Map.of());
    }

    @GetMapping("/features")
    public List<Object> features(@PathVariable UUID deviceId) {
        return List.of();
    }

    @GetMapping("/anomalies")
    public List<Object> anomalies(@PathVariable UUID deviceId) {
        return List.of();
    }

    @GetMapping("/risk")
    public Map<String, Object> risk(@PathVariable UUID deviceId) {
        return Map.of("deviceId", deviceId, "score", 0, "level", "unknown");
    }

    @GetMapping("/recommendations")
    public List<Object> recommendations(@PathVariable UUID deviceId) {
        return List.of();
    }

    @PostMapping("/recalculate")
    public RecalculateResponse recalculate(@PathVariable UUID deviceId) {
        baselineCalculator.recalculate(deviceId);
        riskScoreCalculator.recalculate(deviceId);
        return new RecalculateResponse("accepted", deviceId);
    }
}
