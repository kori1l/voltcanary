package com.voltcanary.device.configurable.domain;

import java.util.List;
import java.util.UUID;

public record NativeQueryPage(List<NativeRow> rows, String nextCursor, ReadManifest readManifest,
        String completeness, String truncationReason) {
    public record ReadManifest(UUID datasetId, long schemaRevision, UUID generation, long dataVersion,
            String schemaDigest, String accessScopeDigest, String queryDigest, String consistency,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID snapshotId) { }
    public NativeQueryPage { rows = List.copyOf(rows); }
}
