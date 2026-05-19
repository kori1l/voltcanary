package com.voltcanary.simulator.yaml;

import java.time.Duration;

public record AnomalyConfig(
        String type,
        boolean enabled,
        Duration startAfter,
        Duration duration,
        Double powerW,
        Double growthPercent,
        Duration rampDuration) {
}
