package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-owned requirements, separate from model proposals and mutation confirmation. */
public final class Requirements {

    public static final String VERSION = "ASSISTANT-COLLAB/1";
    public static final long MAX_REVISION = 9_007_199_254_740_991L;

    private Requirements() {}

    public enum Status {
        UNKNOWN,
        PROPOSED,
        CONFIRMED,
        DERIVED,
        CONFLICT,
        STALE,
    }

    public enum Origin {
        MODEL_PROPOSED,
        USER_CHOICE,
        USER_TEXT,
        USER_VALUES,
        OWNER_EVIDENCE,
        REGISTERED_DERIVATION,
    }

    public record Slot(
        UUID id,
        String path,
        String schemaId,
        boolean required,
        Status status,
        JsonNode value,
        Set<UUID> sourceMessages,
        Set<UUID> evidence,
        Set<UUID> dependsOn,
        long revision,
        Origin origin
    ) {
        public Slot {
            value = value == null || value.isNull() ? null : value;
            sourceMessages = Set.copyOf(sourceMessages);
            evidence = Set.copyOf(evidence);
            dependsOn = Set.copyOf(dependsOn);
            if (
                id == null ||
                path == null ||
                !path.matches("/(?:[^~]|~[01])*") ||
                path.length() > 512 ||
                schemaId == null ||
                schemaId.isBlank() ||
                schemaId.length() > 160 ||
                status == null ||
                origin == null ||
                revision < 0 ||
                revision > MAX_REVISION ||
                dependsOn.size() > 32 ||
                sourceMessages.size() > 32 ||
                evidence.size() > 32
            ) {
                throw invalid("INVALID_REQUIREMENT_SLOT");
            }
            if (
                Set.of(Status.UNKNOWN, Status.CONFLICT, Status.STALE).contains(status) &&
                value != null
            ) {
                throw invalid("UNRESOLVED_SLOT_HAS_VALUE");
            }
            if (
                Set.of(Status.PROPOSED, Status.CONFIRMED, Status.DERIVED).contains(status) &&
                value == null
            ) {
                throw invalid("RESOLVED_SLOT_REQUIRES_VALUE");
            }
            value = value == null ? null : value.deepCopy();
        }

        @Override
        public JsonNode value() {
            return value == null ? null : value.deepCopy();
        }
    }

    public record Choice(String id, String label, Map<UUID, JsonNode> bindings) {
        public Choice {
            if (
                id == null ||
                id.isBlank() ||
                id.length() > 160 ||
                label == null ||
                label.isBlank() ||
                label.length() > 300 ||
                bindings.isEmpty() ||
                bindings.size() > 16
            ) throw invalid("INVALID_REQUIREMENT_CHOICE");
            bindings = copy(bindings);
        }

        @Override
        public Map<UUID, JsonNode> bindings() {
            return copy(bindings);
        }
    }

    public record Question(
        UUID id,
        long revision,
        long requirementsRevision,
        List<UUID> slots,
        String text,
        List<Choice> choices,
        Set<UUID> evidence,
        Instant expiresAt
    ) {
        public Question {
            slots = List.copyOf(slots);
            choices = List.copyOf(choices);
            evidence = Set.copyOf(evidence);
            var allowedSlots = Set.copyOf(slots);
            if (
                id == null ||
                revision < 0 ||
                revision > MAX_REVISION ||
                requirementsRevision < 0 ||
                requirementsRevision > MAX_REVISION ||
                slots.isEmpty() ||
                slots.size() > 16 ||
                Set.copyOf(slots).size() != slots.size() ||
                text == null ||
                text.isBlank() ||
                text.length() > 6000 ||
                choices.size() > 12 ||
                evidence.size() > 32 ||
                expiresAt == null ||
                choices.stream().map(Choice::id).distinct().count() != choices.size() ||
                choices
                    .stream()
                    .anyMatch(choice -> !allowedSlots.containsAll(choice.bindings().keySet()))
            ) {
                throw invalid("INVALID_REQUIREMENT_QUESTION");
            }
        }
    }

    public record Event(
        long revision,
        UUID requestId,
        Set<UUID> changedSlots,
        Set<UUID> invalidatedSlots,
        UUID sourceMessageId,
        Origin origin
    ) {
        public Event {
            changedSlots = Set.copyOf(changedSlots);
            invalidatedSlots = Set.copyOf(invalidatedSlots);
        }
    }

    public record Snapshot(
        String version,
        UUID planId,
        long revision,
        Map<UUID, Slot> slots,
        Question question,
        List<Event> events
    ) {
        public Snapshot {
            slots = Map.copyOf(slots);
            events = List.copyOf(events);
            if (
                !VERSION.equals(version) ||
                planId == null ||
                revision < 0 ||
                revision > MAX_REVISION ||
                slots.size() > 128 ||
                events.size() > 512 ||
                slots
                    .entrySet()
                    .stream()
                    .anyMatch(entry -> !entry.getKey().equals(entry.getValue().id()))
            ) {
                throw invalid("INVALID_REQUIREMENTS_SNAPSHOT");
            }
        }

        public static Snapshot empty(UUID plan) {
            return new Snapshot(VERSION, plan, 0, Map.of(), null, List.of());
        }
    }

    public static final class Rejected extends IllegalArgumentException {

        private final int status;

        public Rejected(int status, String code) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    static Map<UUID, JsonNode> copy(Map<UUID, JsonNode> values) {
        var copied = new java.util.LinkedHashMap<UUID, JsonNode>();
        values.forEach((key, value) -> copied.put(key, value.deepCopy()));
        return Map.copyOf(copied);
    }

    public static Rejected invalid(String code) {
        return new Rejected(422, code);
    }

    public static Rejected conflict(String code) {
        return new Rejected(409, code);
    }
}
