package com.voltcanary.simulator.generator;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GenericApplianceGenerator implements TelemetryGenerator {

    private final MetricValueGenerator metricValueGenerator;
    private final AnomalyInjector anomalyInjector;

    @Override
    public Optional<GeneratedTelemetryPoint> next(SimulationContext context) {
        if (anomalyInjector.offline(context)) {
            return Optional.empty();
        }
        return Optional.of(
            new GeneratedTelemetryPoint(
                context.currentTime(),
                context.sourceId(),
                metricValueGenerator.metrics(context)
            )
        );
    }
}
