package com.voltcanary.ingestion.adapter.api;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;

public interface InboundTelemetryAdapter<T> extends TelemetryAdapter {

    IncomingTelemetryMessage map(T request, SourceRuntimeConfig sourceRuntimeConfig);
}
