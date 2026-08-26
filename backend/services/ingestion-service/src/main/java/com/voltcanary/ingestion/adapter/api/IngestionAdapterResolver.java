package com.voltcanary.ingestion.adapter.api;

import java.util.Optional;

public interface IngestionAdapterResolver {

    Optional<TelemetryAdapter> resolve(SourceRuntimeConfig sourceRuntimeConfig);
}
