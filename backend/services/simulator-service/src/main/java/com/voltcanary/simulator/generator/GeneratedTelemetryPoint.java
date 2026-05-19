package com.voltcanary.simulator.generator;

import java.time.Instant;
import java.util.Map;

public record GeneratedTelemetryPoint(Instant timestamp, String sourceId, Map<String, Object> metrics) {
}
