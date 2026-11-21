package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** An owner-sealed validation result. No raw artifact values enter diagnostics. */
public record NativeImport(UUID id, UUID datasetId, String status, Instant expiresAt,
        String reviewDigest, Integer totalRows, int processedRows, Integer unprocessedRows, int validRows, int invalidRows,
        boolean validationComplete, boolean displayedErrorsTruncated, JsonNode errors,
        JsonNode result, JsonNode typedDiff, JsonNode progress, String outcomeCode) { }
