package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.vcl.VclDocument;
import java.util.List;
import java.util.UUID;

public interface NativeHistoryRows {
    record Generation(UUID generation, long schemaRevision, String schemaDigest, String status,
            long firstDataVersion, long lastDataVersion) { }
    record HistoricalSchema(UUID recordIdFieldId, String schemaDigest, com.fasterxml.jackson.databind.JsonNode definition,
            long firstDataVersion, long lastDataVersion, int maxQueryCuts) { }
    record Generations(List<Generation> items, UUID nextAfterGeneration) {
        public Generations { items = List.copyOf(items); }
    }

    Generations generations(UUID account, UUID actor, UUID dataset, UUID afterGeneration, int limit);
    Generations generations(NativeSharedAccess access, UUID afterGeneration, int limit);
    HistoricalSchema schema(UUID account, UUID actor, UUID dataset, UUID generation);
    HistoricalSchema schema(NativeSharedAccess access, UUID generation);

    record Revision(UUID datasetId, long schemaRevision, UUID generation, long dataVersion, NativeRow row,
            boolean deleted, String recordedAt, String captureKind, String schemaDigest, String accessScopeDigest) { }

    Revision revision(UUID account, UUID actor, UUID dataset, UUID generation, UUID record, long version, List<UUID> select);
    Revision revision(NativeSharedAccess access, UUID generation, UUID record, long version, List<UUID> select);
    NativeQueryPage query(UUID account, UUID actor, UUID generation, long through, VclDocument document);
    NativeQueryPage query(NativeSharedAccess access, UUID generation, long through, VclDocument document);
}
