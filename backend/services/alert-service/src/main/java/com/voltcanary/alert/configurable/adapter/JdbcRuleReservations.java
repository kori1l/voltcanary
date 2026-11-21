package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.configurable.domain.RuleReservation;
import com.voltcanary.alert.configurable.port.RuleReservationStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Short, serialized owner-local transactions. No rule/grant/alert/delivery rows are created. */
@Component
@RequiredArgsConstructor
public class JdbcRuleReservations implements RuleReservationStore {
    private final DataSource source;
    private final VclParser parser = new VclParser();

    @Override
    public RuleReservation reserve(UUID account, UUID actor, List<String> keys, String key) {
        ensureFence(account);
        var request = JsonNodeFactory.instance.objectNode().put("kind", "RULE");
        var names = request.putArray("outputKeys");
        keys.stream().sorted().forEach(names::add);
        String digest = VclCanonical.sha256(VclCanonical.encode(request));
        return transaction(account, connection -> {
            try (var query = connection.prepareStatement("SELECT * FROM cfg_rule_review_reservation WHERE account_id=? AND reservation_key_hash=?")) {
                query.setObject(1, account);
                query.setString(2, VclCanonical.sha256(key));
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (!actor.equals(rows.getObject("actor_id", UUID.class)) || !key.equals(rows.getString("reservation_key"))
                                || !digest.equals(rows.getString("request_digest"))) throw new VclException("IDEMPOTENCY_KEY_REUSED", "");
                        return read(rows);
                    }
                }
            }
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_rule_review_reservation WHERE account_id=? AND status='RESERVED'")) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) >= 100) throw new VclException("REVIEW_QUOTA", "");
                }
            }
            OffsetDateTime now = OffsetDateTime.ofInstant(OwnerDatabaseClock.now(connection), java.time.ZoneOffset.UTC);
            var outputs = JsonNodeFactory.instance.objectNode();
            keys.stream().sorted().forEach(name -> outputs.put(name, UUID.randomUUID().toString()));
            UUID id = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO cfg_rule_review_reservation(id,account_id,actor_id,rule_id,receipt_id,reservation_key_hash,
                    reservation_key,request_digest,output_ids_json,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED',?,?)
                    """, id, account, actor, UUID.randomUUID(), UUID.randomUUID(), VclCanonical.sha256(key), key, digest,
                    VclCanonical.encode(outputs), now, now.plus(Duration.ofMinutes(15)));
            return owned(connection, account, actor, id);
        });
    }

    @Override
    public RuleReservation inspect(UUID account, UUID actor, UUID id) {
        return transaction(account, connection -> owned(connection, account, actor, id));
    }

    private void ensureFence(UUID account) {
        // A race on first insertion is resolved in its own autocommit transaction.
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(true);
            try { execute(connection, "INSERT INTO cfg_rule_owner_fence(account_id) VALUES(?)", account); }
            catch (SQLException failure) { if (!"23505".equals(failure.getSQLState())) throw failure; }
        } catch (SQLException failure) { throw new VclException("RULE_STORAGE_UNAVAILABLE", ""); }
    }

    private RuleReservation owned(Connection connection, UUID account, UUID actor, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_rule_review_reservation WHERE id=? AND account_id=? AND actor_id=?")) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new VclException("REVIEW_UNAVAILABLE", "");
                return read(rows);
            }
        }
    }

    private RuleReservation read(ResultSet rows) throws SQLException {
        var outputs = new LinkedHashMap<String, UUID>();
        var json = parser.readJson(rows.getString("output_ids_json").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        json.fields().forEachRemaining(field -> outputs.put(field.getKey(), UUID.fromString(field.getValue().asText())));
        return new RuleReservation(rows.getObject("id", UUID.class), rows.getObject("rule_id", UUID.class),
                rows.getObject("receipt_id", UUID.class), outputs, rows.getObject("expires_at", OffsetDateTime.class).toInstant(), rows.getString("status"));
    }

    private <T> T transaction(UUID account, Work<T> work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                    execute(connection, "SET LOCAL lock_timeout='2s'");
                    execute(connection, "SET LOCAL statement_timeout='10s'");
                }
                try (var query = connection.prepareStatement("SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE")) {
                    query.setObject(1, account);
                    try (var rows = query.executeQuery()) { if (!rows.next()) throw new VclException("REVIEW_UNAVAILABLE", ""); }
                }
                execute(connection, "UPDATE cfg_rule_review_reservation SET status='EXPIRED' WHERE account_id=? AND status='RESERVED' AND expires_at<=?",
                        account, OffsetDateTime.ofInstant(OwnerDatabaseClock.now(connection), java.time.ZoneOffset.UTC));
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw new VclException("RULE_STORAGE_UNAVAILABLE", ""); }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) query.setObject(index + 1, arguments[index]);
            query.executeUpdate();
        }
    }

    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
}
