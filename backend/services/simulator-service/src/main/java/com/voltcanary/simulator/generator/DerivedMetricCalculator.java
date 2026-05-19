package com.voltcanary.simulator.generator;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DerivedMetricCalculator {

    public double currentA(Map<String, Object> metrics) {
        double activePowerW = ((Number) metrics.getOrDefault("activePowerW", 0)).doubleValue();
        double voltageV = ((Number) metrics.getOrDefault("voltageV", 230)).doubleValue();
        return voltageV == 0 ? 0 : activePowerW / voltageV;
    }

    public String relayState(Map<String, Object> metrics) {
        double activePowerW = ((Number) metrics.getOrDefault("activePowerW", 0)).doubleValue();
        return activePowerW > 5 ? "ON" : "OFF";
    }
}
