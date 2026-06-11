package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsJobProperties;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AnalyticsJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJobWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AnalyticsJobRunner jobRunner;
    private final AnalyticsJobProperties properties;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();

    public AnalyticsJobWorker(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AnalyticsJobRunner jobRunner,
            AnalyticsJobProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.jobRunner = jobRunner;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.analytics.jobs.worker-delay:5s}")
    public void runClaimedJobs() {
        if (!properties.isAsync() || !properties.isWorkerEnabled()) {
            return;
        }
        for (UUID jobId : claimJobs()) {
            try {
                jobRunner.run(jobId);
            } catch (Exception exception) {
                log.warn(
                        "analyticsClaimedJobRunFailed jobId={} reasonCode={}",
                        jobId,
                        exception.getClass().getSimpleName());
            }
        }
    }

    private List<UUID> claimJobs() {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            Instant staleBefore = now.minus(LEASE_TTL);
            List<UUID> ids = claimIds(staleBefore, true);
            if (ids.isEmpty()) {
                return List.of();
            }
            jdbcTemplate.update("""
                    update analytics_jobs
                    set status = 'RUNNING',
                        started_at = coalesce(started_at, ?),
                        locked_at = ?,
                        locked_by = ?,
                        error_message = null
                    where id in (%s)
                    """.formatted(placeholders(ids.size())),
                    parameters(ids, Timestamp.from(now), Timestamp.from(now), workerId));
            return ids;
        });
    }

    private List<UUID> claimIds(Instant staleBefore, boolean skipLocked) {
        String lockClause = skipLocked ? " for update skip locked" : " for update";
        String sql = """
                select id
                from analytics_jobs
                where status = 'PENDING'
                   or (
                        status = 'RUNNING'
                        and locked_at is not null
                        and locked_at < ?
                   )
                order by created_at asc
                limit ?
                """ + lockClause;
        try {
            return jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    Timestamp.from(staleBefore),
                    BATCH_SIZE);
        } catch (DataAccessException exception) {
            if (skipLocked) {
                return claimIds(staleBefore, false);
            }
            throw exception;
        }
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private Object[] parameters(List<UUID> ids, Object... prefix) {
        Object[] parameters = new Object[prefix.length + ids.size()];
        System.arraycopy(prefix, 0, parameters, 0, prefix.length);
        for (int index = 0; index < ids.size(); index++) {
            parameters[prefix.length + index] = ids.get(index);
        }
        return parameters;
    }
}
