package com.voltcanary.identity.scheduling;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SchedulerLockService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String ownerId = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();

    public SchedulerLockService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean acquire(String lockName, Duration lease) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            Instant lockedUntil = now.plus(lease);
            int updated = jdbcTemplate.update("""
                    update scheduler_locks
                    set locked_until = ?, locked_at = ?, locked_by = ?
                    where lock_name = ? and locked_until <= ?
                    """,
                    Timestamp.from(lockedUntil),
                    Timestamp.from(now),
                    ownerId,
                    lockName,
                    Timestamp.from(now));
            if (updated == 1) {
                return true;
            }
            try {
                jdbcTemplate.update("""
                        insert into scheduler_locks (lock_name, locked_until, locked_at, locked_by)
                        values (?, ?, ?, ?)
                        """,
                        lockName,
                        Timestamp.from(lockedUntil),
                        Timestamp.from(now),
                        ownerId);
                return true;
            } catch (DuplicateKeyException ignored) {
                return false;
            }
        });
    }

    public void release(String lockName) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
                update scheduler_locks
                set locked_until = ?, locked_at = ?
                where lock_name = ? and locked_by = ?
                """,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                lockName,
                ownerId));
    }
}
