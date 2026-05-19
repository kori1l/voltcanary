package com.voltcanary.ingestion.pipeline;

import java.util.UUID;

public record IngestionResult(boolean accepted, UUID eventId, String message) {
}
