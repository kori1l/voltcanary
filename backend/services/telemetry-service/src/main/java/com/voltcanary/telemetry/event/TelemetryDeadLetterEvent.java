package com.voltcanary.telemetry.event;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TelemetryDeadLetterEvent(
        UUID deadLetterId,
        String reason,
        List<String> qualityFlags,
        TelemetryReceivedEvent originalEvent,
        Instant failedAt) {
}
