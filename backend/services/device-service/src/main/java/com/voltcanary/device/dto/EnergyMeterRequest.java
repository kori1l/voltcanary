package com.voltcanary.device.dto;

import com.voltcanary.common.web.EnergyMeterDefinition.CounterEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EnergyMeterRequest(long expectedRevision, UUID physicalMeterId, UUID parentPhysicalMeterId,
        String sourceId, String authoritativeMethod, long maxGapSeconds, Instant effectiveFrom,
        List<CounterEvent> counterEvents) {
}
