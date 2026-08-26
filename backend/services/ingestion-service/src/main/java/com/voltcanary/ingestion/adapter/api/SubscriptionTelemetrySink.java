package com.voltcanary.ingestion.adapter.api;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;

public interface SubscriptionTelemetrySink {

    void accept(IncomingTelemetryMessage message);

    void reject(SourceRuntimeConfig sourceRuntimeConfig, RuntimeException exception);
}
