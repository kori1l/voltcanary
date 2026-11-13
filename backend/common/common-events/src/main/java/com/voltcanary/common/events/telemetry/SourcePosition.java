package com.voltcanary.common.events.telemetry;

import java.util.Objects;
import java.util.UUID;

/** Producer-declared position. It proves neither a complete feed nor event-time coverage. */
public record SourcePosition(UUID epoch, String sequence) {
    public SourcePosition {
        Objects.requireNonNull(epoch);
        if (sequence == null || !sequence.matches("[1-9][0-9]{0,17}")) {
            throw new IllegalArgumentException("Invalid producer sequence");
        }
    }

    public long ordinal() {
        return Long.parseLong(sequence);
    }
}
