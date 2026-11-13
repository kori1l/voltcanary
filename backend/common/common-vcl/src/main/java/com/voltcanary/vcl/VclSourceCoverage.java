package com.voltcanary.vcl;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Exact coverage evidence from the telemetry owner. UNKNOWN carries no usable interval. */
public record VclSourceCoverage(UUID proofId, UUID inputGeneration, UUID snapshotId, String coverage,
        String reason, String requiredCheckpoint, Evidence evidence,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant acceptedAt) {
    public VclSourceCoverage(UUID proofId, UUID inputGeneration, UUID snapshotId, String coverage,
            String reason, String requiredCheckpoint, Evidence evidence) {
        this(proofId, inputGeneration, snapshotId, coverage, reason, requiredCheckpoint, evidence, null);
    }
    private static final VclParser PARSER = new VclParser();
    public static final String SOURCE_EVENT_TIME = "SOURCE_EVENT_TIME_V1";
    private static final Set<String> REASONS = Set.of("PROOF_UNAVAILABLE", "JOURNAL_UNAVAILABLE", "GENERATION_CHANGED",
            "SOURCE_GAP", "CONTRADICTORY_OBSERVATION");
    public record Position(UUID epoch, String sequence) { }
    public record Evidence(UUID proofId, UUID inputGeneration, Position position,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant from,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant until,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String eventTimeBasis,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            java.util.List<UUID> parts) {
        public Evidence {
            parts = java.util.List.copyOf(parts);
        }
        public Evidence(UUID proofId, UUID inputGeneration, Position position, Instant from, Instant until) {
            this(proofId, inputGeneration, position, from, until, null, java.util.List.of());
        }
    }

    public boolean qualifiesEventTime() {
        return "COVERED".equals(coverage) && acceptedAt != null && evidence != null
                && SOURCE_EVENT_TIME.equals(evidence.eventTimeBasis());
    }

    public String eventTimeDigest() {
        if (!qualifiesEventTime()) throw new VclException("SOURCE_COVERAGE_PROOF_INVALID", "");
        return VclCanonical.sha256(String.join("\n", SOURCE_EVENT_TIME, proofId.toString(), snapshotId.toString(),
                inputGeneration.toString(), requiredCheckpoint, acceptedAt.toString(), evidence.position().epoch().toString(),
                evidence.position().sequence(), evidence.from().toString(), evidence.until().toString(),
                String.join(",", evidence.parts().stream().map(UUID::toString).toList())));
    }

    public static VclSourceCoverage parse(byte[] bytes) {
        try {
            var json = PARSER.readJson(bytes);
            require(json.isObject() && (json.size() == 7 && !json.has("acceptedAt")
                    || json.size() == 8 && json.has("acceptedAt")));
            UUID proofId = uuid(json.path("proofId").textValue());
            UUID snapshotId = uuid(json.path("snapshotId").textValue());
            if ("UNKNOWN".equals(json.path("coverage").textValue())) {
                String reason = json.path("reason").textValue();
                require(!json.has("acceptedAt") && reason != null && REASONS.contains(reason) && json.path("inputGeneration").isNull()
                        && json.path("requiredCheckpoint").isNull() && json.path("evidence").isNull());
                return new VclSourceCoverage(proofId, null, snapshotId, "UNKNOWN", reason, null, null);
            }
            require("COVERED".equals(json.path("coverage").textValue()) && json.path("reason").isNull());
            UUID generation = uuid(json.path("inputGeneration").textValue());
            String checkpoint = json.path("requiredCheckpoint").textValue();
            require(VclObservationReadiness.checkpoint(checkpoint) > 0);
            var evidence = json.path("evidence");
            require(evidence.isObject() && evidence.size() == 5 + (evidence.has("eventTimeBasis") ? 1 : 0)
                    + (evidence.has("parts") ? 1 : 0)
                    && proofId.equals(uuid(evidence.path("proofId").textValue()))
                    && generation.equals(uuid(evidence.path("inputGeneration").textValue())));
            var position = evidence.path("position");
            String sequence = position.path("sequence").textValue();
            require(position.isObject() && position.size() == 2 && sequence != null && sequence.matches("[1-9][0-9]{0,17}"));
            Instant from = Instant.parse(evidence.path("from").textValue());
            Instant until = Instant.parse(evidence.path("until").textValue());
            var parts = new java.util.ArrayList<UUID>();
            if (evidence.has("parts")) {
                var array = evidence.path("parts");
                require(array.isArray() && array.size() >= 2 && array.size() <= 64);
                for (var item : array) parts.add(uuid(item.textValue()));
                require(!parts.contains(proofId) && Set.copyOf(parts).size() == parts.size());
            }
            require(from.isBefore(until) && from.getNano() % 1000 == 0 && until.getNano() % 1000 == 0
                    && java.time.Duration.between(from, until).compareTo(parts.isEmpty()
                            ? java.time.Duration.ofHours(24) : VclCoverageSearch.MAX_INTERVAL) <= 0);
            Instant acceptedAt = json.has("acceptedAt") ? Instant.parse(json.path("acceptedAt").textValue()) : null;
            require(acceptedAt == null || !acceptedAt.isBefore(until) && acceptedAt.getNano() % 1000 == 0
                    && acceptedAt.toString().equals(json.path("acceptedAt").textValue()));
            String basis = evidence.path("eventTimeBasis").textValue();
            require(!evidence.has("eventTimeBasis") || acceptedAt != null && SOURCE_EVENT_TIME.equals(basis));
            require(parts.isEmpty() || SOURCE_EVENT_TIME.equals(basis));
            return new VclSourceCoverage(proofId, generation, snapshotId, "COVERED", null, checkpoint,
                    new Evidence(proofId, generation, new Position(uuid(position.path("epoch").textValue()), sequence), from, until, basis, parts),
                    acceptedAt);
        } catch (RuntimeException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    private static UUID uuid(String text) {
        UUID value = UUID.fromString(text);
        require(value.toString().equals(text));
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException();
    }
}
