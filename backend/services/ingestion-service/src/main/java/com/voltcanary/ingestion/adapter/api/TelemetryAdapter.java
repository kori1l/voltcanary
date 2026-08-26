package com.voltcanary.ingestion.adapter.api;

public interface TelemetryAdapter {

    AdapterManifest manifest();

    default String adapterId() {
        return manifest().adapterId();
    }

    default AdapterType adapterType() {
        return manifest().adapterType();
    }
}
