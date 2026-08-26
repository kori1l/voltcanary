package com.voltcanary.ingestion.adapter.opcua;

import java.time.Instant;

public record OpcUaNodeValue(
        OpcUaNodeMapping mapping,
        Object value,
        Instant sourceTimestamp,
        Instant serverTimestamp) {
}
