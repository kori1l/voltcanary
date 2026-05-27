package com.voltcanary.ingestion.dto;

import java.util.List;
import java.util.UUID;

public record IngestionAcceptedResponse(
        String status,
        int acceptedCount,
        int duplicateCount,
        int rejectedCount,
        UUID eventId,
        List<BatchIngestionItemResponse> items) {

    public static IngestionAcceptedResponse single(String status, int acceptedCount, int duplicateCount, UUID eventId) {
        return new IngestionAcceptedResponse(status, acceptedCount, duplicateCount, 0, eventId, List.of());
    }

    public static IngestionAcceptedResponse batch(
            String status,
            int acceptedCount,
            int duplicateCount,
            int rejectedCount,
            List<BatchIngestionItemResponse> items) {
        return new IngestionAcceptedResponse(status, acceptedCount, duplicateCount, rejectedCount, null, items);
    }
}
