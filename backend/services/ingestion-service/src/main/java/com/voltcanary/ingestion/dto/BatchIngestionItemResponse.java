package com.voltcanary.ingestion.dto;

import java.util.UUID;

public record BatchIngestionItemResponse(
        int index,
        String status,
        UUID eventId,
        String reason) {
}
