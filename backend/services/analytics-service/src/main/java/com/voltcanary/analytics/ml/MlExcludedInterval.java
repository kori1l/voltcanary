package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.UUID;

public record MlExcludedInterval(
        UUID maintenanceWindowId,
        Instant startAt,
        Instant endAt,
        String reason) {
}
