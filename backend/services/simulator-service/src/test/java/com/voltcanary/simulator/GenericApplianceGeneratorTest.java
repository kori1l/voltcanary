package com.voltcanary.simulator;

import com.voltcanary.simulator.generator.GenericApplianceGenerator;
import com.voltcanary.simulator.generator.AnomalyInjector;
import com.voltcanary.simulator.generator.ConstantMetricGenerator;
import com.voltcanary.simulator.generator.CycleMetricGenerator;
import com.voltcanary.simulator.generator.DerivedMetricCalculator;
import com.voltcanary.simulator.generator.EnergyAccumulator;
import com.voltcanary.simulator.generator.MetricValueGenerator;
import com.voltcanary.simulator.generator.SimulationContext;
import com.voltcanary.simulator.yaml.AnomalyConfig;
import com.voltcanary.simulator.yaml.SimulationConfigModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenericApplianceGeneratorTest {

    @Test
    void producesPowerAndEnergyMetrics() {
        SimulationConfigModel model = new SimulationConfigModel(1, "test", "FRIDGE", "HTTP", "sim-1", Duration.ofSeconds(5), Duration.ZERO);
        SimulationContext context = new SimulationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sim-1", Instant.now(), Instant.now(), 3, model);

        var point = generator().next(context);

        assertThat(point).isPresent();
        assertThat(point.orElseThrow().metrics()).containsKeys("activePowerW", "energyWh", "voltageV");
    }

    @Test
    void energyAccumulatorIncreasesEnergyWh() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        SimulationConfigModel model = new SimulationConfigModel(1, "test", "FRIDGE", "HTTP", "sim-1", Duration.ofSeconds(5), Duration.ZERO);
        GenericApplianceGenerator generator = generator();

        double first = ((Number) generator.next(new SimulationContext(runId, UUID.randomUUID(), UUID.randomUUID(), "sim-1", startedAt, startedAt, 1, model))
                .orElseThrow().metrics().get("energyWh")).doubleValue();
        double second = ((Number) generator.next(new SimulationContext(runId, UUID.randomUUID(), UUID.randomUUID(), "sim-1", startedAt, startedAt.plusSeconds(5), 2, model))
                .orElseThrow().metrics().get("energyWh")).doubleValue();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void powerSpikeAnomalyChangesActivePowerW() {
        SimulationConfigModel model = modelWith(new AnomalyConfig("POWER_SPIKE", true, Duration.ZERO, Duration.ofSeconds(20), 600.0, null, null));
        SimulationContext context = new SimulationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sim-1", Instant.now(), Instant.now(), 1, model);

        double power = ((Number) generator().next(context).orElseThrow().metrics().get("activePowerW")).doubleValue();

        assertThat(power).isEqualTo(600.0);
    }

    @Test
    void offlineAnomalySkipsSending() {
        SimulationConfigModel model = modelWith(new AnomalyConfig("OFFLINE", true, Duration.ZERO, Duration.ofMinutes(5), null, null, null));
        SimulationContext context = new SimulationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sim-1", Instant.now(), Instant.now(), 1, model);

        assertThat(generator().next(context)).isEmpty();
    }

    private SimulationConfigModel modelWith(AnomalyConfig anomaly) {
        return new SimulationConfigModel(
                1,
                "test",
                "FRIDGE",
                "NORMAL",
                "HTTP",
                new com.voltcanary.simulator.yaml.TargetConfig("sim-1"),
                new com.voltcanary.simulator.yaml.ScheduleConfig(Duration.ofSeconds(5), Duration.ZERO, Duration.ZERO, 1.0),
                java.util.Map.of(),
                List.of(anomaly));
    }

    private GenericApplianceGenerator generator() {
        AnomalyInjector anomalyInjector = new AnomalyInjector();
        return new GenericApplianceGenerator(
                new MetricValueGenerator(
                        new ConstantMetricGenerator(),
                        new CycleMetricGenerator(),
                        new DerivedMetricCalculator(),
                        new EnergyAccumulator(),
                        anomalyInjector),
                anomalyInjector);
    }
}
