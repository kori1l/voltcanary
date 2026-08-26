package com.voltcanary.ingestion.adapter.api;

public interface SubscriptionTelemetryAdapter extends TelemetryAdapter {

    void startSubscription(SourceRuntimeConfig sourceRuntimeConfig, SubscriptionTelemetrySink sink);

    void stopSubscription(SourceRuntimeConfig sourceRuntimeConfig);
}
