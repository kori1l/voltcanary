package com.voltcanary.simulator.generator;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class EnergyAccumulator {

    private final Map<UUID, Double> energyByRun = new ConcurrentHashMap<>();

    public double add(SimulationContext context, double activePowerW) {
        double deltaWh = activePowerW * context.config().interval().toSeconds() / 3600.0;
        return energyByRun.merge(context.runId(), deltaWh, Double::sum);
    }
}
