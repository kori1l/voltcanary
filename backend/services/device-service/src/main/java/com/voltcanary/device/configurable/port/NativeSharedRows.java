package com.voltcanary.device.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.vcl.VclDocument;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface NativeSharedRows {
    record SharedTable(UUID accountId, UUID datasetId, UUID grantId, String label) {}

    record SharedTables(java.util.List<SharedTable> items, UUID nextAfterGrant) {
        public SharedTables {
            items = java.util.List.copyOf(items);
        }
    }

    SharedTables discover(UUID workspace, UUID actor, UUID afterGrant, int limit);
    JsonNode describe(NativeSharedAccess access);
    NativeQueryPage query(NativeSharedAccess access, VclDocument document);
    NativeRowQueries.Snapshot seal(
        NativeSharedAccess access,
        VclDocument document,
        NativeRowQueries.AsOfProjection projection
    );
    NativeQueryPage.ReadManifest inspectAsOf(
        NativeSharedAccess access,
        VclDocument document,
        NativeRowQueries.AsOfProjection projection
    );
    NativeRowQueries.AsOf asOf(
        NativeSharedAccess access,
        VclDocument document,
        UUID intervalField,
        Instant eventTime,
        Map<UUID, JsonNode> keys
    );
    NativeRowQueries.AsOfSplit splitAsOf(
        NativeSharedAccess access,
        VclDocument document,
        UUID intervalField,
        Instant start,
        Instant end,
        Map<UUID, JsonNode> keys
    );
}
