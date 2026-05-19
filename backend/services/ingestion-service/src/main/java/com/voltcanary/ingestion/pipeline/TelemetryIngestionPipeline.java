package com.voltcanary.ingestion.pipeline;

public interface TelemetryIngestionPipeline {
    IngestionResult ingest(IncomingTelemetryMessage message);
}
