package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.configurable.domain.GrantRevocation;
import com.voltcanary.alert.configurable.port.GrantRevocationStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Monotone revoke receipts survive uncertain commit acknowledgement and never renew permission. */
@Component
@RequiredArgsConstructor
public class JdbcGrantRevocations implements GrantRevocationStore {
    private final GrantSql sql;

    @Override
    public GrantRevocation reserve(UUID actor, UUID grantId, long expectedEpoch, String key) {
        UUID account = sql.grantAccount(actor, grantId);
        return sql.transaction(account, connection -> {
            try (var query = connection.prepareStatement("SELECT * FROM cfg_grant_revocation WHERE account_id=? AND key_hash=?")) {
                query.setObject(1, account);
                query.setString(2, VclCanonical.sha256(key));
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        var value = read(rows);
                        require(value.actorId().equals(actor) && value.grantId().equals(grantId) && value.expectedEpoch() == expectedEpoch
                                && value.idempotencyKey().equals(key), "IDEMPOTENCY_KEY_REUSED");
                        return value;
                    }
                }
            }
            var grant = sql.grant(connection, actor, grantId);
            require(grant.epoch() == expectedEpoch, "GRANT_EPOCH_CONFLICT");
            UUID id = UUID.randomUUID();
            GrantSql.execute(connection, """
                    INSERT INTO cfg_grant_revocation(id,receipt_id,grant_id,account_id,actor_id,expected_epoch,key_hash,idempotency_key,status)
                    VALUES(?,?,?,?,?,?,?,?,'PENDING')
                    """, id, UUID.randomUUID(), grantId, account, actor, expectedEpoch, VclCanonical.sha256(key), key);
            return owned(connection, actor, id);
        });
    }

    @Override
    public GrantRevocation complete(UUID actor, UUID id) {
        return sql.transaction(sql.revocationAccount(actor, id), connection -> {
            var review = owned(connection, actor, id);
            if (review.status().equals("SUCCEEDED") || review.status().equals("FAILED")) return review;
            var grant = sql.grant(connection, actor, review.grantId());
            if (grant.epoch() != review.expectedEpoch()) {
                GrantSql.execute(connection, "UPDATE cfg_grant_revocation SET status='FAILED',result_epoch=? WHERE id=?", grant.epoch(), id);
                return owned(connection, actor, id);
            }
            long epoch = grant.status().equals("REVOKED") ? grant.epoch() : Math.addExact(grant.epoch(), 1);
            GrantSql.execute(connection, "UPDATE cfg_automation_grant SET status='REVOKED',epoch=? WHERE id=?", epoch, grant.id());
            GrantSql.execute(connection, "UPDATE cfg_grant_revocation SET status='SUCCEEDED',result_epoch=? WHERE id=?", epoch, id);
            return owned(connection, actor, id);
        });
    }

    @Override
    public GrantRevocation inspect(UUID actor, UUID id) {
        return sql.transaction(sql.revocationAccount(actor, id), connection -> owned(connection, actor, id));
    }

    @Override
    public GrantRevocation unknown(UUID actor, UUID id) {
        return sql.transaction(sql.revocationAccount(actor, id), connection -> {
            owned(connection, actor, id);
            GrantSql.execute(connection, "UPDATE cfg_grant_revocation SET status='UNKNOWN' WHERE id=? AND status='PENDING'", id);
            return owned(connection, actor, id);
        });
    }

    private GrantRevocation owned(Connection connection, UUID actor, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_grant_revocation WHERE id=? AND actor_id=? FOR UPDATE")) {
            query.setObject(1, id);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "REVIEW_UNAVAILABLE");
                return read(rows);
            }
        }
    }

    private static GrantRevocation read(ResultSet row) throws SQLException {
        return new GrantRevocation(row.getObject("id", UUID.class), row.getObject("receipt_id", UUID.class), row.getObject("grant_id", UUID.class),
                row.getObject("account_id", UUID.class), row.getObject("actor_id", UUID.class), row.getLong("expected_epoch"),
                row.getString("idempotency_key"), row.getString("status"), row.getObject("result_epoch", Long.class));
    }

    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
