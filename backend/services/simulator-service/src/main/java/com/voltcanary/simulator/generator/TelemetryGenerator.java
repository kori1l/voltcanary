package com.voltcanary.simulator.generator;

import java.util.Optional;

public interface TelemetryGenerator {
    Optional<GeneratedTelemetryPoint> next(SimulationContext context);
}
