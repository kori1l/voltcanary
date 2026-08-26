package com.voltcanary.ingestion.adapter.opcua;

public record OpcUaNodeMapping(
        String metric,
        String nodeId,
        String namespaceUri,
        String browsePath,
        String dataType,
        double scale,
        double offset,
        String unit) {
}
