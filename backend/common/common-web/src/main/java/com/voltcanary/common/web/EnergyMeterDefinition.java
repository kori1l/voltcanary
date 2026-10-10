package com.voltcanary.common.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Additive registry-owned wire metadata; consumers own their calculations. */
public record EnergyMeterDefinition(long revision, UUID physicalMeterId, UUID parentPhysicalMeterId,
        String sourceId, String authoritativeMethod, long maxGapSeconds, Instant effectiveFrom,
        Instant effectiveTo, List<CounterEvent> counterEvents, List<UUID> ancestorPhysicalMeterIds, boolean topologyComplete) {
    public EnergyMeterDefinition {
        counterEvents = counterEvents == null ? List.of() : List.copyOf(counterEvents);
        ancestorPhysicalMeterIds = ancestorPhysicalMeterIds == null ? List.of() : List.copyOf(ancestorPhysicalMeterIds);
    }

    public EnergyMeterDefinition(long revision,UUID physicalMeterId,UUID parentPhysicalMeterId,String sourceId,
            String authoritativeMethod,long maxGapSeconds,Instant effectiveFrom,Instant effectiveTo,List<CounterEvent> events) {
        this(revision,physicalMeterId,parentPhysicalMeterId,sourceId,authoritativeMethod,maxGapSeconds,effectiveFrom,
                effectiveTo,events,parentPhysicalMeterId==null?List.of():List.of(parentPhysicalMeterId),parentPhysicalMeterId==null);
    }

    public record CounterEvent(Instant at, String kind, Double rolloverWh) {
    }
}
