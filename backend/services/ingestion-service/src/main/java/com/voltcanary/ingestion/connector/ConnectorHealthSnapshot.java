package com.voltcanary.ingestion.connector;

import java.time.Instant;

public record ConnectorHealthSnapshot(
        String sourceId,
        ConnectorRuntimeState state,
        int consecutiveFailures,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        Instant nextAttemptAt,
        String message) {
}
