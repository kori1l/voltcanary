package com.voltcanary.simulator.yaml;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record SimulationConfigModel(
    int schemaVersion,
    String name,
    String profile,
    String mode,
    String protocol,
    TargetConfig target,
    ScheduleConfig schedule,
    Map<String, MetricConfig> metrics,
    List<AnomalyConfig> anomalies,
    GpsConfig gps
) {
    public SimulationConfigModel(
        int schemaVersion,
        String name,
        String profile,
        String protocol,
        String sourceId,
        Duration interval,
        Duration duration
    ) {
        this(
            schemaVersion,
            name,
            profile,
            "NORMAL",
            protocol,
            new TargetConfig(sourceId),
            new ScheduleConfig(interval, Duration.ZERO, duration, 1.0),
            Map.of(),
            List.of(),
            null
        );
    }

    public SimulationConfigModel(
        int schemaVersion,
        String name,
        String profile,
        String mode,
        String protocol,
        TargetConfig target,
        ScheduleConfig schedule,
        Map<String, MetricConfig> metrics,
        List<AnomalyConfig> anomalies
    ) {
        this(
            schemaVersion,
            name,
            profile,
            mode,
            protocol,
            target,
            schedule,
            metrics,
            anomalies,
            null
        );
    }

    public String sourceId() {
        return target.sourceId();
    }

    public Duration interval() {
        return schedule.interval();
    }

    public Duration duration() {
        return schedule.duration();
    }
}
