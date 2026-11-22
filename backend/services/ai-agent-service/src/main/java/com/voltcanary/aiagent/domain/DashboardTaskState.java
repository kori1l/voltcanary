package com.voltcanary.aiagent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Working intent belongs to the task, independently of model output and executable reviews. */
public record DashboardTaskState(
    int schemaVersion,
    UUID taskId,
    UUID branchId,
    UUID userId,
    UUID accountId,
    long revision,
    String originalRequest,
    Fact name,
    Selection selection,
    UUID providerId,
    UUID modelConfigId,
    UUID lastAcceptedReviewId,
    UUID activeTurnId,
    String status,
    String failureCategory,
    Instant expiresAt,
    List<PendingChange> pending
) {
    public enum Provenance {
        USER_EXPLICIT,
        USER_ACCEPTED_DEFAULT,
    }

    public enum Disposition {
        PENDING,
        APPLIED,
        FAILED,
        SUPERSEDED,
    }

    public record Fact(String value, Provenance provenance, UUID turnId, long revision) {
        public Fact {
            if (
                value == null ||
                value.isBlank() ||
                value.length() > 80 ||
                provenance == null ||
                turnId == null
            ) {
                throw new IllegalArgumentException("Invalid confirmed dashboard name");
            }
        }
    }

    public record SelectionOrigin(UUID conversationId, UUID reviewId, UUID actionId) {}

    public record Selection(
        List<UUID> deviceIds,
        String sourceId,
        String metric,
        String from,
        String to,
        Map<UUID, String> sourcesByDevice,
        SelectionOrigin origin
    ) {
        public Selection(
            List<UUID> deviceIds,
            String sourceId,
            String metric,
            String from,
            String to,
            Map<UUID, String> sourcesByDevice
        ) {
            this(deviceIds, sourceId, metric, from, to, sourcesByDevice, null);
        }

        public Selection(
            List<UUID> deviceIds,
            String sourceId,
            String metric,
            String from,
            String to
        ) {
            this(deviceIds, sourceId, metric, from, to, Map.of());
        }

        public Selection {
            deviceIds = List.copyOf(deviceIds);
            if (deviceIds.size() > 100) throw new IllegalArgumentException(
                "Selection exceeds the resource budget"
            );
            sourcesByDevice = sourcesByDevice == null ? Map.of() : Map.copyOf(sourcesByDevice);
            if (
                !deviceIds.containsAll(sourcesByDevice.keySet()) ||
                sourcesByDevice.size() > 20 ||
                sourcesByDevice
                    .values()
                    .stream()
                    .anyMatch(value -> value.isBlank() || value.length() > 255)
            ) {
                throw new IllegalArgumentException("Invalid per-device source selection");
            }
            if (
                sourcesByDevice.isEmpty() && sourceId != null && deviceIds.size() == 1
            ) sourcesByDevice = Map.of(deviceIds.getFirst(), sourceId);
        }
    }

    public record PendingChange(
        UUID changeId,
        UUID turnId,
        String request,
        Disposition disposition
    ) {}

    public DashboardTaskState {
        if (
            schemaVersion != 1 ||
            revision < 0 ||
            taskId == null ||
            branchId == null ||
            userId == null ||
            accountId == null ||
            selection == null ||
            expiresAt == null ||
            originalRequest == null ||
            originalRequest.isBlank() ||
            originalRequest.length() > 8000 ||
            !List.of("NEW", "PENDING", "READY", "NEEDS_INPUT", "FAILED").contains(status)
        ) {
            throw new IllegalArgumentException("Invalid dashboard task state");
        }
        pending = List.copyOf(pending);
        if (pending.size() > 31) throw new IllegalArgumentException(
            "Start a new task after thirty continuations"
        );
    }

    public DashboardTaskState begin(UUID turnId, String message) {
        if (activeTurnId != null) throw new IllegalStateException(
            "A dashboard turn is already in progress"
        );
        var changes = new ArrayList<>(pending);
        changes.add(new PendingChange(UUID.randomUUID(), turnId, message, Disposition.PENDING));
        return copy(revision + 1, name, lastAcceptedReviewId, turnId, "PENDING", null, changes);
    }

    public DashboardTaskState confirmName(String value, Provenance provenance) {
        if (activeTurnId == null) throw new IllegalStateException("No accepted user turn");
        return copy(
            revision,
            new Fact(value, provenance, activeTurnId, revision),
            lastAcceptedReviewId,
            activeTurnId,
            status,
            failureCategory,
            pending
        );
    }

    public DashboardTaskState confirmSource(String sourceId) {
        return confirmSource(
            sourceId,
            selection.deviceIds().size() == 1 ? selection.deviceIds().getFirst() : null
        );
    }

    public DashboardTaskState confirmSource(String sourceId, UUID deviceId) {
        if (
            activeTurnId == null ||
            sourceId == null ||
            sourceId.isBlank() ||
            sourceId.length() > 255
        ) throw new IllegalStateException("No accepted source selection");
        var sources = new java.util.LinkedHashMap<>(selection.sourcesByDevice());
        if (deviceId != null) sources.put(deviceId, sourceId);
        return new DashboardTaskState(
            schemaVersion,
            taskId,
            branchId,
            userId,
            accountId,
            revision,
            originalRequest,
            name,
            new Selection(
                selection.deviceIds(),
                sourceId,
                selection.metric(),
                selection.from(),
                selection.to(),
                sources,
                selection.origin()
            ),
            providerId,
            modelConfigId,
            lastAcceptedReviewId,
            activeTurnId,
            status,
            failureCategory,
            expiresAt,
            pending
        );
    }

    public DashboardTaskState finish(UUID reviewId, boolean ready, String failure) {
        if (activeTurnId == null) throw new IllegalStateException("No accepted user turn");
        // A prepared candidate is not proof that every pending goal was fulfilled.
        var changes = pending
            .stream()
            .map(change ->
                failure != null && change.turnId().equals(activeTurnId)
                    ? new PendingChange(
                          change.changeId(),
                          change.turnId(),
                          change.request(),
                          Disposition.FAILED
                      )
                    : change
            )
            .toList();
        if (ready && reviewId == null) throw new IllegalArgumentException(
            "A ready candidate needs an immutable review"
        );
        return copy(
            revision,
            name,
            ready && failure == null ? reviewId : lastAcceptedReviewId,
            null,
            failure != null ? "FAILED" : ready ? "READY" : "NEEDS_INPUT",
            failure,
            changes
        );
    }

    /** An explicit exact restore cancels later pending work; no model-inferred layout is accepted here. */
    public DashboardTaskState restoreTo(
        DashboardTaskState snapshot,
        String restoredName,
        Selection restoredSelection
    ) {
        if (activeTurnId == null) throw new IllegalStateException("No accepted restore turn");
        if (
            snapshot != null &&
            (!taskId.equals(snapshot.taskId()) ||
                !userId.equals(snapshot.userId()) ||
                !accountId.equals(snapshot.accountId()))
        ) {
            throw new IllegalArgumentException("Restore must belong to the same owned task");
        }
        var retained = snapshot == null ? List.<PendingChange>of() : snapshot.pending();
        var changes = pending
            .stream()
            .map(change -> {
                if (change.turnId().equals(activeTurnId)) return change;
                return retained
                    .stream()
                    .filter(old -> old.changeId().equals(change.changeId()))
                    .findFirst()
                    .orElse(
                        new PendingChange(
                            change.changeId(),
                            change.turnId(),
                            change.request(),
                            Disposition.SUPERSEDED
                        )
                    );
            })
            .toList();
        Fact confirmed =
            snapshot != null && snapshot.name() != null
                ? snapshot.name()
                : new Fact(restoredName, Provenance.USER_ACCEPTED_DEFAULT, activeTurnId, revision);
        return new DashboardTaskState(
            schemaVersion,
            taskId,
            branchId,
            userId,
            accountId,
            revision,
            originalRequest,
            confirmed,
            restoredSelection,
            providerId,
            modelConfigId,
            lastAcceptedReviewId,
            activeTurnId,
            status,
            failureCategory,
            expiresAt,
            changes
        );
    }

    private DashboardTaskState copy(
        long nextRevision,
        Fact nextName,
        UUID review,
        UUID active,
        String nextStatus,
        String failure,
        List<PendingChange> changes
    ) {
        return new DashboardTaskState(
            schemaVersion,
            taskId,
            branchId,
            userId,
            accountId,
            nextRevision,
            originalRequest,
            nextName,
            selection,
            providerId,
            modelConfigId,
            review,
            active,
            nextStatus,
            failure,
            expiresAt,
            changes
        );
    }
}
