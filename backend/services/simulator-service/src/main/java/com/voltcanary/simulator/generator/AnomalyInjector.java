package com.voltcanary.simulator.generator;

import com.voltcanary.simulator.yaml.AnomalyConfig;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class AnomalyInjector {

    public boolean offline(SimulationContext context) {
        return active(context, "OFFLINE");
    }

    public double activePower(SimulationContext context, double basePowerW) {
        for (AnomalyConfig anomaly : context.config().anomalies()) {
            if (!anomaly.enabled() || !isActive(context, anomaly)) {
                continue;
            }
            if ("POWER_SPIKE".equals(anomaly.type()) && anomaly.powerW() != null) {
                return anomaly.powerW();
            }
            if ("CONSUMPTION_GROWTH".equals(anomaly.type()) && anomaly.growthPercent() != null) {
                double multiplier = 1.0 + anomaly.growthPercent() / 100.0;
                if (anomaly.rampDuration() != null && !anomaly.rampDuration().isZero()) {
                    Duration elapsed = Duration.between(context.startedAt(), context.currentTime()).minus(anomaly.startAfter());
                    double progress = Math.min(1.0, Math.max(0.0, (double) elapsed.toMillis() / anomaly.rampDuration().toMillis()));
                    return basePowerW * (1.0 + (multiplier - 1.0) * progress);
                }
                return basePowerW * multiplier;
            }
        }
        return basePowerW;
    }

    private boolean active(SimulationContext context, String type) {
        return context.config().anomalies().stream()
                .anyMatch(anomaly -> anomaly.enabled() && type.equals(anomaly.type()) && isActive(context, anomaly));
    }

    private boolean isActive(SimulationContext context, AnomalyConfig anomaly) {
        Duration elapsed = Duration.between(context.startedAt(), context.currentTime());
        Duration start = anomaly.startAfter() == null ? Duration.ZERO : anomaly.startAfter();
        Duration duration = anomaly.duration() == null || anomaly.duration().isZero() ? Duration.ofDays(3650) : anomaly.duration();
        return !elapsed.minus(start).isNegative() && elapsed.compareTo(start.plus(duration)) < 0;
    }
}
