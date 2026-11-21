package com.voltcanary.telemetry.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared manual/AI owner: settings, CAS and the original receipt commit atomically. */
@Service
public class ReviewedVehicleMonitoring {
    public record Snapshot(long revision, String digest, VehicleMonitoringSettings settings) { }
    public record Proposal(long expectedRevision, String idempotencyKey, VehicleMonitoringSettings settings) { }
    public record Confirmation(String digest, String idempotencyKey) { }
    public record Review(UUID id, UUID deviceId, long baseRevision, VehicleMonitoringSettings before,
            VehicleMonitoringSettings after, String digest, String idempotencyKey, Instant expiresAt,
            String status, Long appliedRevision) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public ReviewedVehicleMonitoring(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    }

    public Snapshot current(UUID user, UUID device) {
        return jdbc.query("select revision,settings from telemetry.vehicle_monitoring where user_id=? and device_id=?",
                (row, index) -> snapshot(row.getLong(1), decode(row.getString(2))), user, device)
                .stream().findFirst().orElseGet(() -> snapshot(0, VehicleMonitoringSettings.defaults()));
    }

    @Transactional
    public Review prepare(UUID user, UUID device, Proposal proposal) {
        if (proposal == null || proposal.settings() == null || proposal.expectedRevision() < 0
                || proposal.idempotencyKey() == null || !proposal.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}")) {
            throw failure(HttpStatus.BAD_REQUEST, "Invalid monitoring proposal");
        }
        proposal.settings().validate();
        fence(user, device);
        var previous = jdbc.query("select id from telemetry.vehicle_monitoring_reviews where user_id=? and device_id=? and idempotency_key=?",
                (row, index) -> row.getObject(1, UUID.class), user, device, proposal.idempotencyKey());
        if (!previous.isEmpty()) {
            var review = inspect(user, device, previous.getFirst());
            if (review.baseRevision() != proposal.expectedRevision() || !review.after().equals(proposal.settings())) {
                throw failure(HttpStatus.CONFLICT, "Proposal identity already belongs to another change");
            }
            return review;
        }
        var base = current(user, device);
        if (base.revision() != proposal.expectedRevision()) throw failure(HttpStatus.CONFLICT, "Monitoring settings changed; reload and review again");
        UUID id = UUID.randomUUID();
        Instant expiry = now().plusSeconds(1800);
        String before = encode(base.settings()), after = encode(proposal.settings());
        String digest = hash(user + "\n" + device + "\n" + id + "\n" + base.revision() + "\n" + before + "\n" + after);
        jdbc.update("insert into telemetry.vehicle_monitoring_reviews (id,user_id,device_id,base_revision,before_settings,after_settings,digest,idempotency_key,expires_at) values (?,?,?,?,?,?,?,?,?)",
                id, user, device, base.revision(), before, after, digest, proposal.idempotencyKey(), Timestamp.from(expiry));
        return inspect(user, device, id);
    }

    public Review inspect(UUID user, UUID device, UUID id) {
        Instant time = now();
        return jdbc.query("select * from telemetry.vehicle_monitoring_reviews where id=? and user_id=? and device_id=?",
                (row, index) -> {
                    Long applied = row.getObject("applied_revision", Long.class);
                    Instant expires = row.getTimestamp("expires_at").toInstant();
                    return new Review(id, device, row.getLong("base_revision"), decode(row.getString("before_settings")),
                            decode(row.getString("after_settings")), row.getString("digest"), row.getString("idempotency_key"),
                            expires, applied != null ? "SUCCEEDED" : time.isBefore(expires) ? "PREPARED" : "EXPIRED", applied);
                }, id, user, device).stream().findFirst().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "Monitoring review unavailable"));
    }

    @Transactional
    public Review confirm(UUID user, UUID device, UUID id, Confirmation confirmation) {
        fence(user, device);
        var review = inspect(user, device, id);
        if (confirmation == null || !review.digest().equals(confirmation.digest())
                || !review.idempotencyKey().equals(confirmation.idempotencyKey())) {
            throw failure(HttpStatus.CONFLICT, "Monitoring confirmation differs from the review");
        }
        if (review.status().equals("SUCCEEDED")) return review;
        if (!now().isBefore(review.expiresAt())) throw failure(HttpStatus.GONE, "Monitoring review expired");
        if (current(user, device).revision() != review.baseRevision()) throw failure(HttpStatus.CONFLICT, "Monitoring settings changed; reload and review again");
        long next = Math.addExact(review.baseRevision(), 1);
        if (review.baseRevision() == 0) {
            jdbc.update("insert into telemetry.vehicle_monitoring (user_id,device_id,settings,updated_at,revision) values (?,?,?,?,?)",
                    user, device, encode(review.after()), Timestamp.from(now()), next);
        } else {
            int changed = jdbc.update("update telemetry.vehicle_monitoring set settings=?,updated_at=?,revision=? where user_id=? and device_id=? and revision=?",
                    encode(review.after()), Timestamp.from(now()), next, user, device, review.baseRevision());
            if (changed != 1) throw failure(HttpStatus.CONFLICT, "Monitoring settings changed");
        }
        jdbc.update("update telemetry.vehicle_monitoring_reviews set applied_revision=? where id=?", next, id);
        return inspect(user, device, id);
    }

    private void fence(UUID user, UUID device) {
        jdbc.update(postgres
                ? "insert into telemetry.vehicle_monitoring_fences (user_id,device_id) values (?,?) on conflict do nothing"
                : "merge into telemetry.vehicle_monitoring_fences (user_id,device_id) key(user_id,device_id) values (?,?)", user, device);
        jdbc.queryForObject("select device_id from telemetry.vehicle_monitoring_fences where user_id=? and device_id=? for update", UUID.class, user, device);
    }
    private Instant now() {
        return jdbc.queryForObject(postgres ? "select clock_timestamp()" : "select current_timestamp", java.time.OffsetDateTime.class).toInstant();
    }
    private Snapshot snapshot(long revision, VehicleMonitoringSettings settings) {
        return new Snapshot(revision, hash(encode(settings)), settings);
    }
    private String encode(VehicleMonitoringSettings settings) {
        try { return mapper.writeValueAsString(settings); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid monitoring settings", invalid); }
    }
    private VehicleMonitoringSettings decode(String settings) {
        try { return mapper.readValue(settings, VehicleMonitoringSettings.class); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid stored monitoring settings", invalid); }
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
