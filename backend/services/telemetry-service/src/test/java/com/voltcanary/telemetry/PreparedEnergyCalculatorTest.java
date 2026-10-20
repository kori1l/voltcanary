package com.voltcanary.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Policy;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Method;
import com.voltcanary.telemetry.reference.LegacyEnergyPeriodCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.AbstractList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PreparedEnergyCalculatorTest {
    private final EnergyPeriodCalculator calculator = new EnergyPeriodCalculator();
    private final LegacyEnergyPeriodCalculator legacy = new LegacyEnergyPeriodCalculator();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant base = Instant.parse("2026-10-01T00:00:00Z");

    @Test void overlappingCyclesNormalizeRawSamplesOnce() {
        List<Reading> values = java.util.stream.IntStream.range(0,20000)
                .mapToObj(index -> new Reading(base.plusSeconds(index),index,"Wh","canonical","OK")).toList();
        var samples = new CountingList<>(values);
        var oldSamples = new CountingList<>(values.stream().map(sample -> new LegacyEnergyPeriodCalculator.Reading(
                sample.measuredAt(),sample.value(),sample.unit(),sample.sourceId(),sample.quality())).toList());
        var prepared = calculator.prepare(samples,Method.COUNTER_DELTA,"canonical");
        var policy = new Policy(Method.COUNTER_DELTA,"canonical",900,List.of());
        for (int index=0;index<32;index++) {
            Instant from=base.plusSeconds(index*100L), to=from.plusSeconds(100);
            var actual=calculator.calculate(prepared.between(base,base.plusSeconds(20000),true),from,to,policy);
            var expected=legacy.calculate(oldSamples,from,to,new LegacyEnergyPeriodCalculator.Policy(
                    LegacyEnergyPeriodCalculator.Method.COUNTER_DELTA,"canonical",900,List.of()));
            assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(actual)).isEqualTo(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(expected));
        }
        assertThat(samples.visits).isEqualTo(20000);
        assertThat(oldSamples.visits).isEqualTo(640000);
        System.out.println("32 cycles: raw normalization visits legacy="+oldSamples.visits+" prepared="+samples.visits);
    }

    private static final class CountingList<T> extends AbstractList<T> {
        private final List<T> values;
        private int visits;
        CountingList(List<T> values) { this.values=values; }
        @Override public int size() { return values.size(); }
        @Override public T get(int index) { visits++; return values.get(index); }
    }

    @Test void allFieldsMatchLegacyAcrossRandomWindowsQualityGapsAndCounterEvents() {
        var random = new Random(41032);
        for (Method method : Method.values()) {
            List<Reading> samples = new ArrayList<>();
            for (int index = 0; index < 800; index++) {
                double value = method == Method.COUNTER_DELTA ? index % 120 * 50 : random.nextInt(2000);
                String unit = method == Method.COUNTER_DELTA ? "Wh" : "W";
                if (index % 31 == 0) value = Double.NaN;
                if (index % 37 == 0) unit = "unknown";
                Instant time = base.plusSeconds(index * 60L + (index > 400 ? 3600 : 0));
                var sample = new Reading(time, value, unit, "canonical", index % 23 == 0 ? "SUSPECT" : "OK");
                samples.add(sample);
                if (index % 19 == 0) samples.add(sample);
                if (index % 17 == 0) samples.add(new Reading(time, value + 1, unit, "canonical", "OK"));
            }
            Collections.shuffle(samples, random);
            var events = List.of(new EnergyPeriodCalculator.CounterEvent(base.plusSeconds(7200),
                    EnergyPeriodCalculator.EventKind.RESET, null),
                    new EnergyPeriodCalculator.CounterEvent(base.plusSeconds(14400),
                            EnergyPeriodCalculator.EventKind.ROLLOVER, 6000.0));
            var policy = new Policy(method, "canonical", 900, events);
            var prepared = calculator.prepare(samples, method, "canonical");
            for (int iteration = 0; iteration < 300; iteration++) {
                Instant from = base.plusSeconds(random.nextInt(50000) - 4000);
                Instant to = from.plusSeconds(1 + random.nextInt(7200));
                Instant readFrom = from.minusSeconds(random.nextInt(10000));
                Instant readTo = to.plusSeconds(random.nextInt(10000));
                boolean inclusive = random.nextBoolean();
                List<Reading> selected = samples.stream().filter(sample -> !sample.measuredAt().isBefore(readFrom)
                        && (inclusive ? !sample.measuredAt().isAfter(readTo) : sample.measuredAt().isBefore(readTo))).toList();
                var oldPolicy = new LegacyEnergyPeriodCalculator.Policy(
                        LegacyEnergyPeriodCalculator.Method.valueOf(method.name()), "canonical", 900,
                        events.stream().map(event -> new LegacyEnergyPeriodCalculator.CounterEvent(event.at(),
                                LegacyEnergyPeriodCalculator.EventKind.valueOf(event.kind().name()), event.rolloverWh())).toList());
                var expected = legacy.calculate(selected.stream().map(sample -> new LegacyEnergyPeriodCalculator.Reading(
                        sample.measuredAt(), sample.value(), sample.unit(), sample.sourceId(), sample.quality())).toList(), from, to, oldPolicy);
                var actual = calculator.calculate(prepared.between(readFrom, readTo, inclusive), from, to, policy);
                assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(actual)).as("%s window%d", method, iteration).isEqualTo(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(expected));
            }
        }
    }
}
