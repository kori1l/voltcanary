package com.voltcanary.simulator;

import com.voltcanary.simulator.yaml.SimulationYamlParser;
import com.voltcanary.simulator.yaml.SimulationYamlValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationYamlValidatorTest {

    private final SimulationYamlValidator validator = new SimulationYamlValidator(new SimulationYamlParser());

    @Test
    void acceptsValidYaml() {
        assertThat(validator.validate(validYaml())).isEmpty();
    }

    @Test
    void rejectsInvalidProfile() {
        assertThat(validator.validate(validYaml().replace("FRIDGE", "UNKNOWN_DEVICE")))
                .anyMatch(error -> error.path().equals("simulator.profile"));
    }

    @Test
    void rejectsInvalidInterval() {
        assertThat(validator.validate(validYaml().replace("5s", "0s")))
                .anyMatch(error -> error.path().equals("schedule.interval"));
    }

    @Test
    void rejectsInvalidCycleDuration() {
        assertThat(validator.validate(validYaml().replace("min: 8m", "min: 20m").replace("max: 16m", "max: 10m")))
                .anyMatch(error -> error.path().equals("metrics.activePowerW.cycle.onDuration"));
    }

    private String validYaml() {
        return """
                schemaVersion: 1
                simulator:
                  name: test
                  profile: FRIDGE
                  protocol: HTTP
                target:
                  sourceId: sim-fridge-001
                schedule:
                  interval: 5s
                  duration: 30m
                metrics:
                  activePowerW:
                    type: cycle
                    offValue:
                      mean: 2.0
                    onValue:
                      mean: 120.0
                    cycle:
                      onDuration:
                        min: 8m
                        max: 16m
                      offDuration:
                        min: 20m
                        max: 45m
                  currentA:
                    type: derived
                    expression: "activePowerW / voltageV"
                  energyWh:
                    type: accumulator
                    from: activePowerW
                """;
    }
}
