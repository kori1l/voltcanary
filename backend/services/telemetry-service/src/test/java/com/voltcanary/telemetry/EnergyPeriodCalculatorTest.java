package com.voltcanary.telemetry;

import com.voltcanary.telemetry.domain.EnergyPeriodCalculator;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.CounterEvent;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.EventKind;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Method;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Policy;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyPeriodCalculatorTest {
    private final EnergyPeriodCalculator calculator = new EnergyPeriodCalculator();
    private final Instant from = Instant.parse("2026-10-08T00:00:00Z");
    private Reading at(long second, double value, String unit) {
        return new Reading(from.plusSeconds(second), value, unit, "meter-source", "OK");
    }
    private Policy policy(Method method, long gap) {
        return new Policy(method, "meter-source", gap, List.of());
    }

    @Test
    void cumulativeConsumptionIsDeltaWithExplicitHalfOpenBoundary() {
        var result = calculator.calculate(List.of(at(0,10000,"Wh"),at(3600,10500,"Wh")),
                from,from.plusSeconds(3600),policy(Method.COUNTER_DELTA,3600));
        assertEquals(0.5,result.energyKWh(),0.000001);
        assertEquals("COMPLETE",result.coverage());
        assertEquals(1,result.acceptedSamples());
        assertEquals(1,result.coverageFraction());
        var bounded=calculator.calculate(List.of(at(0,10000,"Wh"),at(3600,10500,"Wh")),from.plusSeconds(900),from.plusSeconds(2700),policy(Method.COUNTER_DELTA,3600));
        assertEquals(0.25,bounded.energyKWh(),0.000001);
        assertTrue(bounded.estimate());
        assertEquals("RAW_CONSUMPTION_INCLUDES_MAINTENANCE",result.maintenancePolicy());
    }

    @Test
    void negativeCounterAndReplacementDoNotCreateAbsoluteConsumption() {
        var readings=List.of(at(0,10000,"Wh"),at(1800,10500,"Wh"),at(3600,100,"Wh"),at(5400,200,"Wh"));
        var result=calculator.calculate(readings,from,from.plusSeconds(5400),policy(Method.COUNTER_DELTA,1800));
        assertEquals(0.6,result.energyKWh(),0.000001);
        assertEquals("PARTIAL",result.coverage());
        assertEquals(1,result.resetIntervals());
        var replacement=new Policy(Method.COUNTER_DELTA,"meter-source",1800,
                List.of(new CounterEvent(from.plusSeconds(1800),EventKind.REPLACEMENT,null)));
        var replaced=calculator.calculate(readings,from,from.plusSeconds(5400),replacement);
        assertEquals(0.1,replaced.energyKWh(),0.000001);
        assertEquals(2,replaced.resetIntervals());
    }

    @Test
    void aRolloverNeedsAnExplicitEventAndModulus() {
        var readings=List.of(at(0,900,"Wh"),at(3600,100,"Wh"));
        var unknown=calculator.calculate(readings,from,from.plusSeconds(3600),policy(Method.COUNTER_DELTA,3600));
        assertNull(unknown.energyKWh());
        var policy=new Policy(Method.COUNTER_DELTA,"meter-source",3600,
                List.of(new CounterEvent(from.plusSeconds(3600),EventKind.ROLLOVER,1000d)));
        var known=calculator.calculate(readings,from,from.plusSeconds(3600),policy);
        assertEquals(0.2,known.energyKWh(),0.000001);
        assertEquals(1,known.rolloverIntervals());
        assertThrows(IllegalArgumentException.class,()->new Policy(Method.COUNTER_DELTA,"meter-source",3600,
                List.of(new CounterEvent(from,EventKind.ROLLOVER,null))));
    }

    @Test
    void constantPowerIsTimeWeightedAndExcessiveGapIsNotIntegrated() {
        var full=calculator.calculate(List.of(at(0,1000,"W"),at(7200,1,"kW")),
                from,from.plusSeconds(7200),policy(Method.POWER_INTEGRAL,7200));
        assertEquals(2,full.energyKWh(),0.000001);
        assertTrue(full.estimate());
        var gap=calculator.calculate(List.of(at(0,1000,"W"),at(1800,1000,"W"),at(7200,1000,"W")),
                from,from.plusSeconds(7200),policy(Method.POWER_INTEGRAL,3600));
        assertEquals(0.5,gap.energyKWh(),0.000001);
        assertEquals(0.25,gap.coverageFraction());
        assertEquals(1,gap.excessiveGaps());
        assertEquals("PARTIAL",gap.coverage());
    }

    @Test
    void unevenPowerUsesElapsedTimeAndBoundaryInterpolationRatherThanSampleMean() {
        var full=calculator.calculate(List.of(at(7200,1000,"W"),at(0,0,"W"),at(3600,1000,"W")),
                from,from.plusSeconds(7200),policy(Method.POWER_INTEGRAL,3600));
        assertEquals(1.5,full.energyKWh(),0.000001);
        var bounded=calculator.calculate(List.of(at(0,0,"W"),at(3600,1000,"W")),
                from.plusSeconds(900),from.plusSeconds(2700),policy(Method.POWER_INTEGRAL,3600));
        assertEquals(0.25,bounded.energyKWh(),0.000001);
        assertEquals("COMPLETE",bounded.coverage());
        var missing=calculator.calculate(List.of(at(3600,1000,"W"),at(7200,1000,"W")),
                from,from.plusSeconds(7200),policy(Method.POWER_INTEGRAL,3600));
        assertEquals("PARTIAL",missing.coverage());
        assertEquals(0.5,missing.coverageFraction());
    }

    @Test
    void duplicateConflictBadQualityAndMixedFamiliesAreBarriersAndNeverZero() {
        var identical=calculator.calculate(List.of(at(3600,10.5,"kWh"),at(0,10000,"Wh"),at(0,10,"kWh")),
                from,from.plusSeconds(3600),policy(Method.COUNTER_DELTA,3600));
        assertEquals(0.5,identical.energyKWh(),0.000001);
        assertEquals(1,identical.duplicateSamples());
        var conflicting=calculator.calculate(List.of(at(0,100,"W"),at(1800,100,"W"),at(1800,200,"W"),
                at(1800,100,"W"),at(3600,100,"W")),from,from.plusSeconds(3600),policy(Method.POWER_INTEGRAL,3600));
        assertNull(conflicting.energyKWh());
        assertEquals(1,conflicting.conflictingTimestamps());
        var badQuality=new Reading(from.plusSeconds(1800),100,"W","meter-source","INVALID");
        var excluded=calculator.calculate(List.of(at(0,100,"W"),badQuality,at(3600,100,"W")),
                from,from.plusSeconds(3600),policy(Method.POWER_INTEGRAL,3600));
        assertNull(excluded.energyKWh());
        var incompatible=calculator.calculate(List.of(at(0,100,"Wh"),at(3600,100,"W")),
                from,from.plusSeconds(3600),policy(Method.POWER_INTEGRAL,3600));
        assertNull(incompatible.energyKWh());
        var zero=calculator.calculate(List.of(at(0,0,"W"),at(3600,0,"W")),
                from,from.plusSeconds(3600),policy(Method.POWER_INTEGRAL,3600));
        assertEquals(0,zero.energyKWh());
        assertEquals("COMPLETE",zero.coverage());
    }
}
