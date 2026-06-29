package com.voltcanary.simulator.dto;

import java.util.List;

public record SimulationPresetResponse(
        String id,
        String name,
        String description,
        List<String> metrics,
        List<String> expectedAlerts,
        String defaultDuration,
        String defaultInterval) {
}
