package com.voltcanary.dashboard.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DashboardRecords {
    private DashboardRecords() {
    }

    public record Dashboard(UUID id, UUID ownerUserId, UUID accountId, String name,
            String description, String status, int publishedRevision, int schemaVersion,
            Instant createdAt, Instant updatedAt) {
    }

    public record Draft(UUID dashboardId, UUID authorUserId, int baseRevision, UUID etag,
            DashboardDefinition definition, Instant updatedAt) {
    }

    public record Revision(UUID dashboardId, int revision, DashboardDefinition definition,
            UUID publishedBy, Instant publishedAt) {
    }

    public record Preferences(UUID defaultDashboardId, List<UUID> favorites,
            JsonNode defaultContext, UUID etag, Instant updatedAt, String fallbackReason) {
    }

    public record Idempotency(String bodyHash, JsonNode outcome, Instant expiresAt) {
    }

    public record Cursor(Instant createdAt, UUID id) {
    }
}
