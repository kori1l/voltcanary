package com.voltcanary.simulator.dto;

import java.util.List;

public record SimulationPresetResponse(
    String id,
    String name,
    String description,
    List<String> metrics,
    List<String> expectedAlerts,
    String defaultDuration,
    String defaultInterval,
    List<String> compatibleProfiles
) {
    public SimulationPresetResponse(
        String id,
        String name,
        String description,
        List<String> metrics,
        List<String> expectedAlerts,
        String defaultDuration,
        String defaultInterval
    ) {
        this(
            id,
            name,
            description,
            metrics,
            expectedAlerts,
            defaultDuration,
            defaultInterval,
            List.of("GENERIC_APPLIANCE", "FRIDGE", "AIR_CONDITIONER", "BOILER", "PUMP", "HEATER")
        );
    }
}
