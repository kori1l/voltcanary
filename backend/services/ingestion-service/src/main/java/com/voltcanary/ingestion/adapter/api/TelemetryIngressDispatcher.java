package com.voltcanary.ingestion.adapter.api;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;

public interface TelemetryIngressDispatcher<T> {

    IncomingTelemetryMessage dispatch(T request, SourceRuntimeConfig sourceRuntimeConfig);
}
