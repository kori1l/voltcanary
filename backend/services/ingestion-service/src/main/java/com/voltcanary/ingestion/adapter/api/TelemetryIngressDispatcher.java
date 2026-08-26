package com.voltcanary.ingestion.adapter.api;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;

public interface TelemetryIngressDispatcher {

    <T> IncomingTelemetryMessage dispatch(
            AdapterType adapterType,
            ProtocolType protocol,
            TransportType transport,
            Class<T> requestType,
            T request,
            SourceRuntimeConfig sourceRuntimeConfig);
}
