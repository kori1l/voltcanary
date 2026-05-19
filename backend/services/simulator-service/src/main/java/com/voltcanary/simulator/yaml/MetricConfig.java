package com.voltcanary.simulator.yaml;

import java.time.Duration;

public record MetricConfig(
        String name,
        String type,
        Double value,
        Double mean,
        Double noise,
        Duration onMin,
        Duration onMax,
        Duration offMin,
        Duration offMax,
        String expression,
        String from) {
}
