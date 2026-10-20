package com.voltcanary.simulator.generator;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SimulationGeneratorSelection {
    private final GenericApplianceGenerator appliance;
    private final VehicleGpsGenerator vehicle;

    public SimulationGeneratorSelection(GenericApplianceGenerator appliance, VehicleGpsGenerator vehicle) {
        this.appliance = appliance;
        this.vehicle = vehicle;
    }

    public TelemetryGenerator forProfile(String profile) {
        return "VEHICLE_GPS".equals(profile) ? vehicle : appliance;
    }

    public void release(UUID runId) {
        vehicle.release(runId);
    }
}
