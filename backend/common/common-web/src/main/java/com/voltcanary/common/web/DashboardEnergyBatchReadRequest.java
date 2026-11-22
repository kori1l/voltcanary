package com.voltcanary.common.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** At most 64 keyed intervals under one renewed actor/resource scope and watermark. */
public record DashboardEnergyBatchReadRequest(
    DashboardOwnerReadRequest scope,
    List<Interval> intervals
) {
    public record Interval(
        String key,
        List<UUID> deviceIds,
        Instant from,
        Instant to,
        boolean independentMetersConfirmed
    ) {}
}
