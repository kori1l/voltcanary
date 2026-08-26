package com.voltcanary.ingestion.adapter.api;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.util.List;

public interface PollingTelemetryAdapter extends TelemetryAdapter {

    List<IncomingTelemetryMessage> poll(SourceRuntimeConfig sourceRuntimeConfig);
}
