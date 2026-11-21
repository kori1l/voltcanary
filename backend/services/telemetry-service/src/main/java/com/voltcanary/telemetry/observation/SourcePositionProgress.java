package com.voltcanary.telemetry.observation;

import com.voltcanary.common.events.telemetry.SourcePosition;
import com.voltcanary.vcl.VclObservationReadiness;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Durable contiguous producer positions. The sequence has no event-time coverage authority. */
@Service
@RequiredArgsConstructor
public class SourcePositionProgress {
    private static final String STREAM = "binding_id=? AND input_generation=? AND snapshot_id=? AND producer_epoch=?";
    private final JdbcTemplate jdbc;
    private final ObservationReadiness readiness;

    public record Evidence(UUID inputGeneration, UUID snapshotId, UUID producerEpoch, String contiguousThrough,
            String highestObserved, boolean gapPresent, String upstreamCoverage) { }

    /** Caller holds the binding's journal lock until observation, ranges and outbox commit together. */
    static void record(JdbcTemplate jdbc, UUID binding, UUID generation, UUID snapshot, SourcePosition position) {
        long sequence = position.ordinal();
        var epoch = position.epoch();
        var occupied = jdbc.queryForList("SELECT event_id FROM cfg_observation WHERE " + STREAM + " AND producer_sequence=?",
                UUID.class, binding, generation, snapshot, epoch, sequence);
        if (!occupied.isEmpty()) throw new ObservationCaptureUnavailable("SOURCE_POSITION_CONFLICT");
        var left = jdbc.queryForList("SELECT first_sequence FROM cfg_source_position_range WHERE " + STREAM + " AND last_sequence=?",
                Long.class, binding, generation, snapshot, epoch, sequence - 1);
        var right = jdbc.queryForList("SELECT last_sequence FROM cfg_source_position_range WHERE " + STREAM + " AND first_sequence=?",
                Long.class, binding, generation, snapshot, epoch, sequence + 1);
        if (!left.isEmpty()) jdbc.update("DELETE FROM cfg_source_position_range WHERE " + STREAM + " AND last_sequence=?",
                binding, generation, snapshot, epoch, sequence - 1);
        if (!right.isEmpty()) jdbc.update("DELETE FROM cfg_source_position_range WHERE " + STREAM + " AND first_sequence=?",
                binding, generation, snapshot, epoch, sequence + 1);
        jdbc.update("""
                INSERT INTO cfg_source_position_range(binding_id,input_generation,snapshot_id,producer_epoch,first_sequence,last_sequence)
                VALUES(?,?,?,?,?,?)
                """, binding, generation, snapshot, epoch, left.isEmpty() ? sequence : left.getFirst(),
                right.isEmpty() ? sequence : right.getFirst());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Evidence read(VclObservationReadiness.Pin pin, UUID epoch) {
        var journal = readiness.read(pin);
        if (!journal.journalReady()) throw new ObservationCaptureUnavailable("SOURCE_CAPTURE_UNAVAILABLE");
        UUID generation = journal.inputGeneration();
        var prefix = jdbc.queryForList("SELECT last_sequence FROM cfg_source_position_range WHERE " + STREAM + " AND first_sequence=1",
                Long.class, pin.bindingId(), generation, pin.snapshotId(), epoch);
        var maximum = jdbc.queryForList("SELECT last_sequence FROM cfg_source_position_range WHERE " + STREAM
                        + " ORDER BY last_sequence DESC LIMIT 1",
                Long.class, pin.bindingId(), generation, pin.snapshotId(), epoch);
        long through = prefix.isEmpty() ? 0 : prefix.getFirst();
        long highest = maximum.isEmpty() ? 0 : maximum.getFirst();
        return new Evidence(generation, pin.snapshotId(), epoch, Long.toString(through), Long.toString(highest), highest > through, "UNKNOWN");
    }
}
