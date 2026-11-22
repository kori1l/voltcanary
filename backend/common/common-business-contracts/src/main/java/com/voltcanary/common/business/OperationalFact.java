package com.voltcanary.common.business;

import java.time.Instant;
import java.util.UUID;

public record OperationalFact(
    UUID eventId,
    int version,
    UUID deviceId,
    UUID sourceId,
    String sourceEventId,
    String kind,
    String completeness,
    Instant startAt,
    Instant endAt,
    String reason,
    String evidence
) {}
