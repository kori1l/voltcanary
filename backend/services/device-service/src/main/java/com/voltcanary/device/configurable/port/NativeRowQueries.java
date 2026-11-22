package com.voltcanary.device.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.vcl.VclDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NativeRowQueries {
    record Snapshot(UUID snapshotId, Instant expiresAt, int rowCount) {}

    record AsOf(
        String status,
        String reason,
        NativeRow row,
        NativeQueryPage.ReadManifest readManifest
    ) {}

    record AsOfSegment(
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant start,
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant end,
        String status,
        String reason,
        NativeRow row
    ) {}

    record AsOfSplit(List<AsOfSegment> segments, NativeQueryPage.ReadManifest readManifest) {
        public AsOfSplit {
            segments = List.copyOf(segments);
        }
    }

    record AsOfProjection(UUID intervalFieldId, List<UUID> keyFieldIds) {
        public AsOfProjection {
            keyFieldIds = List.copyOf(keyFieldIds);
        }
    }

    NativeQueryPage query(UUID account, UUID actor, VclDocument document);
    Snapshot seal(UUID account, UUID actor, VclDocument document);
    Snapshot seal(UUID account, UUID actor, VclDocument document, AsOfProjection projection);
    AsOf asOf(
        UUID account,
        UUID actor,
        VclDocument document,
        UUID effectiveIntervalField,
        Instant eventTime,
        Map<UUID, JsonNode> keys
    );
    AsOfSplit splitAsOf(
        UUID account,
        UUID actor,
        VclDocument document,
        UUID effectiveIntervalField,
        Instant start,
        Instant end,
        Map<UUID, JsonNode> keys
    );
    NativeQueryPage.ReadManifest inspectAsOf(
        UUID account,
        UUID actor,
        VclDocument document,
        AsOfProjection projection
    );
}
