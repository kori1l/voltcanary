package com.voltcanary.telemetry.observation;

import com.voltcanary.common.events.telemetry.SourcePosition;
import com.voltcanary.vcl.VclObservationReadiness;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Evaluates an explicitly trusted completeness statement against durable, gap-free journal evidence. */
@Service
@RequiredArgsConstructor
public class SourceCoverageAttestations {

    private final JdbcTemplate jdbc;
    private final SourcePositionProgress positions;

    public record Proof(
        UUID proofId,
        UUID inputGeneration,
        SourcePosition position,
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant from,
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant until,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
        )
        String eventTimeBasis,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
        )
        java.util.List<UUID> parts
    ) {
        public Proof(
            UUID proofId,
            UUID inputGeneration,
            SourcePosition position,
            Instant from,
            Instant until
        ) {
            this(proofId, inputGeneration, position, from, until, null, java.util.List.of());
        }

        public Proof(
            UUID proofId,
            UUID inputGeneration,
            SourcePosition position,
            Instant from,
            Instant until,
            String basis
        ) {
            this(proofId, inputGeneration, position, from, until, basis, java.util.List.of());
        }

        public Proof {
            parts = java.util.List.copyOf(parts);
            Objects.requireNonNull(proofId);
            Objects.requireNonNull(inputGeneration);
            Objects.requireNonNull(position);
            Objects.requireNonNull(from);
            Objects.requireNonNull(until);
            if (
                eventTimeBasis != null &&
                !com.voltcanary.vcl.VclSourceCoverage.SOURCE_EVENT_TIME.equals(eventTimeBasis)
            ) {
                throw new IllegalArgumentException("Invalid event-time basis");
            }
            if (
                !from.isBefore(until) ||
                from.getNano() % 1000 != 0 ||
                until.getNano() % 1000 != 0 ||
                Duration.between(from, until).compareTo(
                    parts.isEmpty()
                        ? Duration.ofHours(24)
                        : com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL
                ) > 0
            ) {
                throw new IllegalArgumentException("Invalid coverage interval");
            }
        }
    }

    public record Result(
        UUID proofId,
        UUID inputGeneration,
        UUID snapshotId,
        String coverage,
        String reason,
        String requiredCheckpoint,
        Proof evidence,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
        )
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant acceptedAt
    ) {}

    private record StoredProof(Proof proof, Instant acceptedAt) {}

    @Transactional
    public Result attest(VclObservationReadiness.Pin pin, Proof proof) {
        if (!proof.parts().isEmpty()) throw new IllegalArgumentException(
            "Only the owner composes coverage"
        );
        var stream = jdbc.queryForList(
            """
            SELECT binding_id FROM cfg_observation_stream WHERE binding_id=? AND account_id=? AND device_id=? FOR UPDATE
            """,
            UUID.class,
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId()
        );
        if (stream.isEmpty()) throw new ObservationCaptureUnavailable("SOURCE_CAPTURE_UNAVAILABLE");
        if (storedComposite(pin, proof.proofId()) != null) throw new ObservationCaptureUnavailable(
            "SOURCE_COVERAGE_CONFLICT"
        );
        var acceptedAt = Objects.requireNonNull(
            jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class)
        )
            .toInstant()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (proof.until().isAfter(acceptedAt)) throw new ObservationCaptureUnavailable(
            "SOURCE_COVERAGE_TIME_INVALID"
        );
        var existing = stored(pin, proof.proofId());
        if (existing != null) {
            if (!existing.proof().equals(proof)) throw new ObservationCaptureUnavailable(
                "SOURCE_COVERAGE_CONFLICT"
            );
            return evaluate(pin, existing);
        }
        var result = evaluate(pin, new StoredProof(proof, acceptedAt));
        // A trusted statement can arrive before Kafka has filled the producer prefix. Keep it
        // for read-time recovery, but never retain an assertion for a different generation.
        if (
            !"COVERED".equals(result.coverage()) && !"SOURCE_GAP".equals(result.reason())
        ) return result;
        if (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_source_coverage_attestation WHERE binding_id=?",
                Long.class,
                pin.bindingId()
            ) >= 1024
        ) {
            throw new ObservationCaptureUnavailable("SOURCE_COVERAGE_CAPACITY_REACHED");
        }
        jdbc.update(
            """
            INSERT INTO cfg_source_coverage_attestation(proof_id,binding_id,input_generation,snapshot_id,producer_epoch,
                through_sequence,covered_from,covered_until,accepted_at,event_time_basis) VALUES(?,?,?,?,?,?,?,?,?,?)
            """,
            proof.proofId(),
            pin.bindingId(),
            proof.inputGeneration(),
            pin.snapshotId(),
            proof.position().epoch(),
            proof.position().ordinal(),
            Timestamp.from(proof.from()),
            Timestamp.from(proof.until()),
            Timestamp.from(acceptedAt),
            proof.eventTimeBasis()
        );
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result read(VclObservationReadiness.Pin pin, UUID proofId) {
        var existing = stored(pin, proofId);
        if (existing != null) return evaluate(pin, existing);
        var composite = storedComposite(pin, proofId);
        return composite == null
            ? unavailable(pin, proofId)
            : evaluateComposite(pin, proofId, composite);
    }

    @Transactional
    public UUID findQualified(com.voltcanary.vcl.VclCoverageSearch search) {
        var pin = search.pin();
        var stream = jdbc.queryForList(
            """
            SELECT binding_id FROM cfg_observation_stream WHERE binding_id=? AND account_id=? AND device_id=? FOR UPDATE
            """,
            UUID.class,
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId()
        );
        if (stream.isEmpty()) return null;
        var candidates = jdbc.query(
            """
            SELECT p.proof_id,p.producer_epoch,p.covered_from,p.covered_until FROM cfg_source_coverage_attestation p
            JOIN cfg_observation_stream s ON p.binding_id=s.binding_id
            WHERE p.binding_id=? AND p.snapshot_id=? AND p.input_generation=? AND s.account_id=? AND s.device_id=?
                AND p.event_time_basis=? AND p.covered_from<? AND p.covered_until>?
            ORDER BY p.producer_epoch,p.covered_until DESC,p.covered_from,p.proof_id FETCH FIRST 1024 ROWS ONLY
            """,
            (row, index) ->
                new Segment(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getObject(3, OffsetDateTime.class).toInstant(),
                    row.getObject(4, OffsetDateTime.class).toInstant()
                ),
            pin.bindingId(),
            pin.snapshotId(),
            search.generation(),
            pin.accountId(),
            pin.deviceId(),
            com.voltcanary.vcl.VclSourceCoverage.SOURCE_EVENT_TIME,
            Timestamp.from(search.until()),
            Timestamp.from(search.from())
        );
        var groups = new java.util.LinkedHashMap<UUID, java.util.List<Segment>>();
        candidates.forEach(segment ->
            groups
                .computeIfAbsent(segment.epoch(), ignored -> new java.util.ArrayList<>())
                .add(segment)
        );
        var checked = new java.util.HashMap<UUID, Long>();
        java.util.function.ToLongFunction<Segment> checkpoint = segment ->
            checked.computeIfAbsent(segment.id(), id -> {
                var saved = stored(pin, id);
                var result = saved == null ? null : evaluate(pin, saved);
                return result != null && "COVERED".equals(result.coverage())
                    ? Long.parseLong(result.requiredCheckpoint())
                    : -1L;
            });
        for (var segments : groups.values()) {
            var parts = cover(search, segments, checkpoint, null);
            if (!parts.isEmpty()) return saveCoverageSearch(pin, search, parts);
            for (var anchor : segments) {
                if (checkpoint.applyAsLong(anchor) < search.minimumCheckpoint()) continue;
                parts = cover(search, segments, checkpoint, anchor);
                if (!parts.isEmpty()) return saveCoverageSearch(pin, search, parts);
            }
        }
        return null;
    }

    private UUID saveCoverageSearch(
        VclObservationReadiness.Pin pin,
        com.voltcanary.vcl.VclCoverageSearch search,
        java.util.List<UUID> parts
    ) {
        return parts.size() == 1
            ? parts.getFirst()
            : saveComposite(
                  pin,
                  new Composite(search.generation(), search.from(), search.until(), parts)
              );
    }

    private static java.util.List<UUID> cover(
        com.voltcanary.vcl.VclCoverageSearch search,
        java.util.List<Segment> segments,
        java.util.function.ToLongFunction<Segment> checkpoint,
        Segment anchor
    ) {
        var parts = new java.util.ArrayList<UUID>();
        Instant frontier = search.from();
        long through = -1;
        while ((frontier.isBefore(search.until()) || anchor != null) && parts.size() < 64) {
            Instant point = frontier;
            Segment next;
            if (anchor != null && !anchor.from().isAfter(point)) {
                next = anchor;
                anchor = null;
            } else {
                var required = anchor;
                next = segments
                    .stream()
                    .filter(
                        segment -> !segment.from().isAfter(point) && segment.until().isAfter(point)
                    )
                    .filter(
                        segment -> required == null || segment.until().isBefore(required.until())
                    )
                    .filter(segment -> checkpoint.applyAsLong(segment) >= 0)
                    .findFirst()
                    .orElse(null);
            }
            if (next == null) break;
            parts.add(next.id());
            through = Math.max(through, checkpoint.applyAsLong(next));
            frontier = next.until();
        }
        return !frontier.isBefore(search.until()) && through >= search.minimumCheckpoint()
            ? java.util.List.copyOf(parts)
            : java.util.List.of();
    }

    private record Segment(UUID id, UUID epoch, Instant from, Instant until) {}

    private record Composite(
        UUID generation,
        Instant from,
        Instant until,
        java.util.List<UUID> parts
    ) {}

    private static UUID compositeId(VclObservationReadiness.Pin pin, Composite composite) {
        String identity =
            "SOURCE_COVERAGE_COMPOSITE/1\n" +
            pin.accountId() +
            "\n" +
            pin.bindingId() +
            "\n" +
            pin.snapshotId() +
            "\n" +
            composite.generation() +
            "\n" +
            composite.from() +
            "\n" +
            composite.until() +
            "\n" +
            encodeParts(composite.parts());
        return UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String encodeParts(java.util.List<UUID> parts) {
        return parts.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
    }

    private UUID saveComposite(VclObservationReadiness.Pin pin, Composite composite) {
        UUID id = compositeId(pin, composite);
        var previous = storedComposite(pin, id);
        if (composite.equals(previous)) return id;
        if (previous != null || stored(pin, id) != null) throw new ObservationCaptureUnavailable(
            "SOURCE_COVERAGE_CONFLICT"
        );
        if (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_source_coverage_composite WHERE binding_id=?",
                Long.class,
                pin.bindingId()
            ) >= 1024
        ) throw new ObservationCaptureUnavailable("SOURCE_COVERAGE_CAPACITY_REACHED");
        jdbc.update(
            """
            INSERT INTO cfg_source_coverage_composite(proof_id,binding_id,snapshot_id,input_generation,
                covered_from,covered_until,part_ids) VALUES (?,?,?,?,?,?,?)
            """,
            id,
            pin.bindingId(),
            pin.snapshotId(),
            composite.generation(),
            Timestamp.from(composite.from()),
            Timestamp.from(composite.until()),
            encodeParts(composite.parts())
        );
        return id;
    }

    private Composite storedComposite(VclObservationReadiness.Pin pin, UUID id) {
        var rows = jdbc.query(
            """
            SELECT c.input_generation,c.covered_from,c.covered_until,c.part_ids
            FROM cfg_source_coverage_composite c JOIN cfg_observation_stream s ON s.binding_id=c.binding_id
            WHERE c.proof_id=? AND c.binding_id=? AND c.snapshot_id=? AND s.account_id=? AND s.device_id=?
            """,
            (row, index) ->
                new Composite(
                    row.getObject(1, UUID.class),
                    row.getObject(2, OffsetDateTime.class).toInstant(),
                    row.getObject(3, OffsetDateTime.class).toInstant(),
                    java.util.Arrays.stream(row.getString(4).split(","))
                        .map(UUID::fromString)
                        .toList()
                ),
            id,
            pin.bindingId(),
            pin.snapshotId(),
            pin.accountId(),
            pin.deviceId()
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Result evaluateComposite(
        VclObservationReadiness.Pin pin,
        UUID id,
        Composite composite
    ) {
        if (
            !id.equals(compositeId(pin, composite)) ||
            composite.parts().size() < 2 ||
            composite.parts().size() > 64
        ) {
            return unavailable(pin, id);
        }
        Instant frontier = composite.from();
        Instant acceptedAt = Instant.MIN;
        UUID epoch = null;
        long sequence = 0;
        long checkpoint = 0;
        for (var part : composite.parts()) {
            var stored = stored(pin, part);
            if (stored == null || stored.acceptedAt() == null) return unavailable(pin, id);
            var proof = stored.proof();
            if (
                !com.voltcanary.vcl.VclSourceCoverage.SOURCE_EVENT_TIME.equals(
                    proof.eventTimeBasis()
                ) ||
                !composite.generation().equals(proof.inputGeneration()) ||
                proof.from().isAfter(frontier) ||
                !proof.until().isAfter(frontier) ||
                (epoch != null && !epoch.equals(proof.position().epoch()))
            ) {
                return unavailable(pin, id);
            }
            var validated = evaluate(pin, stored);
            if (!"COVERED".equals(validated.coverage())) {
                return new Result(
                    id,
                    null,
                    pin.snapshotId(),
                    "UNKNOWN",
                    validated.reason(),
                    null,
                    null,
                    null
                );
            }
            epoch = proof.position().epoch();
            sequence = Math.max(sequence, proof.position().ordinal());
            checkpoint = Math.max(checkpoint, Long.parseLong(validated.requiredCheckpoint()));
            if (stored.acceptedAt().isAfter(acceptedAt)) acceptedAt = stored.acceptedAt();
            frontier = proof.until();
        }
        if (frontier.isBefore(composite.until())) return unavailable(pin, id);
        var proof = new Proof(
            id,
            composite.generation(),
            new SourcePosition(epoch, Long.toString(sequence)),
            composite.from(),
            composite.until(),
            com.voltcanary.vcl.VclSourceCoverage.SOURCE_EVENT_TIME,
            composite.parts()
        );
        return new Result(
            id,
            composite.generation(),
            pin.snapshotId(),
            "COVERED",
            null,
            Long.toString(checkpoint),
            proof,
            acceptedAt
        );
    }

    private static Result unavailable(VclObservationReadiness.Pin pin, UUID id) {
        return new Result(
            id,
            null,
            pin.snapshotId(),
            "UNKNOWN",
            "PROOF_UNAVAILABLE",
            null,
            null,
            null
        );
    }

    private StoredProof stored(VclObservationReadiness.Pin pin, UUID id) {
        var rows = jdbc.query(
            """
            SELECT p.input_generation,p.producer_epoch,p.through_sequence,p.covered_from,p.covered_until,p.accepted_at,p.event_time_basis
            FROM cfg_source_coverage_attestation p JOIN cfg_observation_stream s ON p.binding_id=s.binding_id
            WHERE p.proof_id=? AND p.binding_id=? AND p.snapshot_id=? AND s.account_id=? AND s.device_id=?
            """,
            (row, index) -> {
                var proof = new Proof(
                    id,
                    row.getObject(1, UUID.class),
                    new SourcePosition(row.getObject(2, UUID.class), Long.toString(row.getLong(3))),
                    row.getObject(4, OffsetDateTime.class).toInstant(),
                    row.getObject(5, OffsetDateTime.class).toInstant(),
                    row.getString(7)
                );
                var accepted = row.getObject(6, OffsetDateTime.class);
                return new StoredProof(proof, accepted == null ? null : accepted.toInstant());
            },
            id,
            pin.bindingId(),
            pin.snapshotId(),
            pin.accountId(),
            pin.deviceId()
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Result evaluate(VclObservationReadiness.Pin pin, StoredProof stored) {
        var proof = stored.proof();
        SourcePositionProgress.Evidence evidence;
        try {
            evidence = positions.read(pin, proof.position().epoch());
        } catch (ObservationCaptureUnavailable unavailable) {
            return unknown(pin, proof, "JOURNAL_UNAVAILABLE");
        }
        if (!proof.inputGeneration().equals(evidence.inputGeneration())) return unknown(
            pin,
            proof,
            "GENERATION_CHANGED"
        );
        if (
            Long.parseLong(evidence.contiguousThrough()) < proof.position().ordinal()
        ) return unknown(pin, proof, "SOURCE_GAP");
        var contradictory = jdbc.queryForList(
            """
            SELECT event_id FROM cfg_observation WHERE binding_id=? AND input_generation=? AND
                occurred_at>=? AND occurred_at<? AND
                (snapshot_id<>? OR producer_epoch IS NULL OR producer_epoch<>? OR producer_sequence>?)
            LIMIT 1
            """,
            UUID.class,
            pin.bindingId(),
            proof.inputGeneration(),
            Timestamp.from(proof.from()),
            Timestamp.from(proof.until()),
            pin.snapshotId(),
            proof.position().epoch(),
            proof.position().ordinal()
        );
        if (!contradictory.isEmpty()) return unknown(pin, proof, "CONTRADICTORY_OBSERVATION");
        Long required = jdbc.queryForObject(
            """
            SELECT MAX(checkpoint) FROM cfg_observation WHERE binding_id=? AND input_generation=?
                AND snapshot_id=? AND producer_epoch=? AND producer_sequence<=?
            """,
            Long.class,
            pin.bindingId(),
            proof.inputGeneration(),
            pin.snapshotId(),
            proof.position().epoch(),
            proof.position().ordinal()
        );
        if (required == null || required < 1) return unknown(pin, proof, "JOURNAL_UNAVAILABLE");
        return new Result(
            proof.proofId(),
            proof.inputGeneration(),
            pin.snapshotId(),
            "COVERED",
            null,
            Long.toString(required),
            proof,
            stored.acceptedAt()
        );
    }

    private static Result unknown(VclObservationReadiness.Pin pin, Proof proof, String reason) {
        return new Result(
            proof.proofId(),
            null,
            pin.snapshotId(),
            "UNKNOWN",
            reason,
            null,
            null,
            null
        );
    }
}
