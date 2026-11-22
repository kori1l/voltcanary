package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsJobProperties;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsJobWorker {

    private record Claim(UUID id, String owner) { }

    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AnalyticsJobRunner jobRunner;
    private final AnalyticsJobProperties properties;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();

    @Scheduled(fixedDelayString = "${app.analytics.jobs.worker-delay:5s}")
    public void runClaimedJobs() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        for (int processed = 0; processed < BATCH_SIZE; processed++) {
            var claimed = claimJobs();
            if (claimed.isEmpty()) break;
            Claim claim = claimed.getFirst();
            try {
                jobRunner.run(claim.id(), claim.owner());
            } catch (Exception exception) {
                log.warn(
                        "analyticsClaimedJobRunFailed jobId={} reasonCode={}",
                        claim.id(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private List<Claim> claimJobs() {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            String owner = workerId + ":" + UUID.randomUUID();
            Instant staleBefore = now.minus(LEASE_TTL);
            List<UUID> ids = claimIds(staleBefore);
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
                    parameters(ids, Timestamp.from(now), Timestamp.from(now), owner));
            return ids.stream().map(id -> new Claim(id, owner)).toList();
        });
    }

    private List<UUID> claimIds(Instant staleBefore) {
        String sql = """
                select id
                from analytics_jobs
                where (status = 'PENDING' and ? = true)
                   or (
                        status = 'RUNNING'
                        and locked_at is not null
                        and locked_at < ?
                   )
                order by created_at asc
                limit ?
                """ + " for update skip locked";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("id", UUID.class),
                properties.isAsync(), Timestamp.from(staleBefore), 1);
    }

    private String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
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
