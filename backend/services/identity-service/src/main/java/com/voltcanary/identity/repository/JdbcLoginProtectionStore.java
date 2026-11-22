package com.voltcanary.identity.repository;

import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.service.LoginProtectionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short serialized decisions bound storage and share exact sliding windows across replicas. */
public class JdbcLoginProtectionStore implements LoginProtectionStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int capacity;
    private final Clock clock;

    public JdbcLoginProtectionStore(
        JdbcTemplate jdbc,
        PlatformTransactionManager manager,
        IdentityAuthProperties properties
    ) {
        this(jdbc, manager, properties, null);
    }

    public JdbcLoginProtectionStore(
        JdbcTemplate jdbc,
        PlatformTransactionManager manager,
        IdentityAuthProperties properties,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(5);
        this.capacity = properties.getLogin().getTrackedKeyLimit();
        this.clock = clock;
    }

    @Override
    public <T> T update(Function<Entries, T> operation) {
        return transactions.execute(status -> {
            jdbc.queryForObject(
                "select id from login_protection_guard where id=1 for update",
                Integer.class
            );
            Instant now = now();
            jdbc.update(
                "delete from login_protection_keys where expires_at < ?",
                Timestamp.from(now)
            );
            return operation.apply(new LockedEntries(now));
        });
    }

    private Instant now() {
        if (clock != null) {
            return clock.instant();
        }
        String expression = jdbc.execute(
            (ConnectionCallback<String>) connection ->
                "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                    ? "clock_timestamp()"
                    : "current_timestamp"
        );
        return jdbc.queryForObject("select " + expression, Timestamp.class).toInstant();
    }

    private final class LockedEntries implements Entries {

        private final Instant now;

        private LockedEntries(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public State get(String key) {
            List<State> states = jdbc.query(
                """
                select attempts, failures, locked_until, expires_at
                from login_protection_keys where key_hash=?
                """,
                (row, index) ->
                    new State(
                        decode(row.getString("attempts")),
                        row.getInt("failures"),
                        row.getTimestamp("locked_until") == null
                            ? null
                            : row.getTimestamp("locked_until").toInstant(),
                        row.getTimestamp("expires_at").toInstant()
                    ),
                hash(key)
            );
            return states.isEmpty() ? null : states.getFirst();
        }

        @Override
        public void put(String key, State state) {
            String hashed = hash(key);
            String attempts = state
                .attempts()
                .stream()
                .map(Instant::toString)
                .collect(Collectors.joining(","));
            Timestamp locked =
                state.lockedUntil() == null ? null : Timestamp.from(state.lockedUntil());
            int updated = jdbc.update(
                """
                update login_protection_keys set attempts=?, failures=?, locked_until=?, expires_at=?
                where key_hash=?
                """,
                attempts,
                state.failures(),
                locked,
                Timestamp.from(state.expiresAt()),
                hashed
            );
            if (updated == 0) {
                if (
                    jdbc.queryForObject(
                        "select count(*) from login_protection_keys",
                        Integer.class
                    ) >= capacity
                ) {
                    throw new AuthenticationFailedException("Invalid email or password");
                }
                jdbc.update(
                    """
                    insert into login_protection_keys(key_hash,attempts,failures,locked_until,expires_at)
                    values(?,?,?,?,?)
                    """,
                    hashed,
                    attempts,
                    state.failures(),
                    locked,
                    Timestamp.from(state.expiresAt())
                );
            }
        }

        @Override
        public void remove(String key) {
            jdbc.update("delete from login_protection_keys where key_hash=?", hash(key));
        }
    }

    private static List<Instant> decode(String attempts) {
        return attempts.isEmpty()
            ? List.of()
            : Arrays.stream(attempts.split(",")).map(Instant::parse).toList();
    }

    private static String hash(String key) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
