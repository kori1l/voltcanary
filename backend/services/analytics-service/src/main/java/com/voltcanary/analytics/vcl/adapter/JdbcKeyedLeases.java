package com.voltcanary.analytics.vcl.adapter;

import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.VERSION;

import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Lease;
import com.voltcanary.analytics.vcl.KeyedStateStore.Snapshot;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.vcl.VclCanonical;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Focused leases operations sharing the original state-store transaction boundary. */
final class JdbcKeyedLeases {

    private final JdbcKeyedStateStore store;

    JdbcKeyedLeases(JdbcKeyedStateStore store) {
        this.store = store;
    }

    public Snapshot register(Key key, String pinsDigest, State initial) {
        // Validate the immutable pin using the same public value contract as worker leases.
        if (
            pinsDigest == null || !pinsDigest.matches("[0-9a-f]{64}")
        ) throw new IllegalArgumentException("Invalid execution digest");
        String registration = VclCanonical.sha256(
            VERSION + "\n" + key.digest() + "\n" + pinsDigest + "\n" + initial.digest()
        );
        try (var connection = store.connection()) {
            try (
                var insert = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    INSERT INTO vcl_keyed_state(account_id,key_digest,state_version,registration_digest,pins_digest,
                    state_revision,state_digest,state_payload,inbox_count,lease_fence,rule_id,deployment_epoch,subject_digest,occurrence_digest)
                    VALUES(?,?,?,?,?,0,?,?,0,0,?,?,?,?)
                    """
                )
            ) {
                JdbcKeyedStateStore.bindKey(insert, key);
                insert.setString(3, VERSION);
                insert.setString(4, registration);
                insert.setString(5, pinsDigest);
                insert.setString(6, initial.digest());
                insert.setString(7, initial.payload());
                insert.setObject(8, key.ruleId());
                insert.setObject(9, key.deploymentEpoch());
                insert.setString(10, key.subjectDigest());
                insert.setString(11, key.occurrenceDigest());
                insert.executeUpdate();
                return new Snapshot(0, initial);
            }
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState())) throw JdbcKeyedStateStore.unavailable();
        }
        try (var connection = store.connection()) {
            var row = store.load(connection, key, false);
            JdbcKeyedStateStore.require(
                registration.equals(row.registration()),
                "KEY_REGISTRATION_CHANGED"
            );
            return row.snapshot();
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    public Snapshot inspect(Key key, String pinsDigest) {
        try (var connection = store.connection()) {
            var row = store.load(connection, key, false);
            JdbcKeyedStateStore.require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return row.snapshot();
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    public Lease acquire(Key key, String pinsDigest, UUID holderId) {
        java.util.Objects.requireNonNull(holderId);
        return store.transaction(connection -> {
            var row = store.load(connection, key, true);
            JdbcKeyedStateStore.require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            Instant now = JdbcKeyedStateStore.now(connection);
            JdbcKeyedStateStore.require(
                row.expiry() == null ||
                    !now.isBefore(row.expiry()) ||
                    holderId.equals(row.holder()),
                "KEY_LEASE_BUSY"
            );
            JdbcKeyedStateStore.require(row.fence() < Long.MAX_VALUE, "KEY_FENCE_LIMIT");
            long fence = row.fence() + 1;
            Instant expiry = now.plusSeconds(30);
            try (
                var update = JdbcKeyedStateStore.prepare(
                    connection,
                    "UPDATE vcl_keyed_state SET" +
                        " lease_holder=?,lease_fence=?,lease_expires_at=? WHERE" +
                        " account_id=? AND key_digest=?"
                )
            ) {
                update.setObject(1, holderId);
                update.setLong(2, fence);
                update.setObject(3, expiry.atOffset(ZoneOffset.UTC));
                update.setObject(4, key.accountId());
                update.setString(5, key.digest());
                JdbcKeyedStateStore.require(update.executeUpdate() == 1, "KEY_UNAVAILABLE");
            }
            return new Lease(
                holderId,
                fence,
                key.digest(),
                pinsDigest,
                expiry,
                row.snapshot(),
                JdbcKeyedTimers.read(connection, key, row.snapshot().revision())
            );
        });
    }
}
