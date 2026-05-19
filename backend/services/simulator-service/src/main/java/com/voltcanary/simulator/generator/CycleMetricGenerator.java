package com.voltcanary.simulator.generator;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class CycleMetricGenerator {

    public double activePower(SimulationContext context) {
        long period = Math.floorMod(context.tickNumber(), 10);
        if (period < 3) {
            return jitter(120, 8);
        }
        return jitter(2, 0.5);
    }

    private double jitter(double mean, double spread) {
        return mean + ThreadLocalRandom.current().nextDouble(-spread, spread);
    }
}
