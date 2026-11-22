package com.voltcanary.telemetry.observation;

import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import com.voltcanary.vcl.VclObservationProfile;
import com.voltcanary.vcl.VclObservationReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A consistent owner SQL read: no generation minting, repair or upstream/runtime attestation. */
@Service
@RequiredArgsConstructor
public class ObservationReadiness {

    private final JdbcTemplate jdbc;
    private final ObservationSchemas schemas;
    private final ObservationGenerations generations;
    private final ObservationCaptureProperties properties;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VclObservationReadiness read(VclObservationReadiness.Pin pin) {
        if (!properties.captures(pin.bindingId())) return VclObservationReadiness.absent(
            pin,
            "DISABLED"
        );
        var closed = jdbc.queryForList(
            "SELECT closed FROM device_deletion_fences WHERE device_id=?",
            Boolean.class,
            pin.deviceId()
        );
        if (!closed.isEmpty() && closed.getFirst()) return VclObservationReadiness.absent(
            pin,
            "DISABLED"
        );
        var schemaPin = new SourceBindingSchemaPin(
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId(),
            pin.externalSourceId(),
            pin.schemaRevision(),
            pin.snapshotId(),
            pin.definitionDigest()
        );
        if (schemas.find(schemaPin).isEmpty()) return VclObservationReadiness.absent(
            pin,
            "SCHEMA_PENDING"
        );
        var generation = generations.find(pin.bindingId());
        boolean pending =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_observation_input WHERE binding_id=? AND status='PENDING'",
                Long.class,
                pin.bindingId()
            ) > 0;
        if (generation.isEmpty()) return VclObservationReadiness.absent(
            pin,
            pending ? "INPUT_PENDING" : "AWAITING_INPUT"
        );
        var value = generation.get();
        long head = jdbc.queryForObject(
            "SELECT head_checkpoint FROM cfg_observation_stream WHERE binding_id=?",
            Long.class,
            pin.bindingId()
        );
        boolean ready = !pending && head > value.startCheckpoint();
        String state = pending ? "INPUT_PENDING" : ready ? "READY" : "AWAITING_INPUT";
        return new VclObservationReadiness(
            pin,
            VclObservationProfile.VERSION,
            state,
            value.id(),
            Long.toString(value.startCheckpoint()),
            Long.toString(head),
            value.startedAt(),
            ready,
            false,
            false,
            "UNKNOWN",
            "UNKNOWN",
            "UNQUALIFIED",
            null
        );
    }
}
