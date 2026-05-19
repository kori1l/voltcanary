package com.voltcanary.simulator.yaml;

import java.time.Duration;

public record ScheduleConfig(Duration interval, Duration startDelay, Duration duration, double speed) {
}
