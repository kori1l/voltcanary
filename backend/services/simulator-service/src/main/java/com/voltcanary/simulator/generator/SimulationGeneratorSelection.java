package com.voltcanary.simulator.generator;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimulationGeneratorSelection {

    private final GenericApplianceGenerator appliance;
    private final VehicleGpsGenerator vehicle;

    public TelemetryGenerator forProfile(String profile) {
        return "VEHICLE_GPS".equals(profile) ? vehicle : appliance;
    }

    public void release(UUID runId) {
        vehicle.release(runId);
    }
}
