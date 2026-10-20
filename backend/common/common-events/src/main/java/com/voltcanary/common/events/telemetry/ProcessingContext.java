package com.voltcanary.common.events.telemetry;

import java.util.UUID;

public record ProcessingContext(UUID dataSourceId, long generation, String digest, String inputSchemaVersion,
        boolean historicalImport) {
    public ProcessingContext(UUID dataSourceId, long generation, String digest, String inputSchemaVersion) {
        this(dataSourceId, generation, digest, inputSchemaVersion, false);
    }
}
