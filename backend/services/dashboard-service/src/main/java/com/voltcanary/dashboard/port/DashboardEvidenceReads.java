package com.voltcanary.dashboard.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import com.voltcanary.dashboard.query.QueryTypes;
import com.voltcanary.dashboard.query.QueryTypes.Scope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit owner read ports; no arbitrary endpoint, SQL or write operation. */
public interface DashboardEvidenceReads {
    DashboardActor activeActor(String authorization, UUID userId);
    record Device(UUID id, String name, String type, UUID locationId, String registrationStatus,
            Long cadenceSeconds, String cadenceSource, String sourcePolicy, Instant updatedAt) {
    }

    record Location(UUID id, String name, UUID parentId, List<String> tags) {
    }

    record ScopeSnapshot(UUID userId, UUID accountId, long requestedCount, long evaluatedCount,
            long deniedBindingCount, List<Device> devices, List<Location> locations,
            boolean deviceTagsSupported, Instant resolvedAt) {
    }

    record ReadRequest(List<UUID> deviceIds, UUID accountId, Frame time, Set<String> metrics,
            List<UUID> detailDeviceIds, int rows, int offset, String incidentMode, List<QueryTypes.Series> series) {
    }

    ScopeSnapshot scope(String authorization, DashboardActor actor, Scope scope);
    JsonNode telemetry(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode analytics(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode incidents(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode maintenance(String authorization, DashboardActor actor, ReadRequest request);
}
