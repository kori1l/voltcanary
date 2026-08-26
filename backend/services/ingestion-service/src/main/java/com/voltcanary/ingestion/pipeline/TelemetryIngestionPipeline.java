package com.voltcanary.ingestion.pipeline;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;

public interface TelemetryIngestionPipeline {
    IngestionResult ingest(IncomingTelemetryMessage message);
}
