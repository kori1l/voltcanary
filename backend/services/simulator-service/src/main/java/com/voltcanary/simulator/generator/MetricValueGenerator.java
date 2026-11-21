package com.voltcanary.simulator.generator;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricValueGenerator {

    private final ConstantMetricGenerator constantMetricGenerator;
    private final CycleMetricGenerator cycleMetricGenerator;
    private final DerivedMetricCalculator derivedMetricCalculator;
    private final EnergyAccumulator energyAccumulator;
    private final AnomalyInjector anomalyInjector;

    public Map<String, Object> metrics(SimulationContext context) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        double voltage = constantMetricGenerator.value(230, 1.5);
        double activePower = anomalyInjector.activePower(context, cycleMetricGenerator.activePower(context));
        metrics.put("activePowerW", round(activePower));
        metrics.put("voltageV", round(voltage));
        metrics.put("currentA", round(derivedMetricCalculator.currentA(metrics)));
        metrics.put("energyWh", round(energyAccumulator.add(context, activePower)));
        metrics.put("relayState", derivedMetricCalculator.relayState(metrics));
        metrics.put("rssi", Math.round(constantMetricGenerator.value(-61, 3)));
        return metrics;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
