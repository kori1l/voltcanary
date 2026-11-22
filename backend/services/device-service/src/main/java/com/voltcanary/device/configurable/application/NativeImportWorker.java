package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.port.NativeImports;
import com.voltcanary.vcl.VclException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only persisted, explicitly confirmed imports are eligible; every advance renews owner authority. */
@Component
@ConditionalOnProperty(
    name = "app.native-data.import-worker-enabled",
    havingValue = "true",
    matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class NativeImportWorker {

    private record Pending(UUID id, UUID account, UUID actor, long revision) {}

    private final JdbcTemplate jdbc;
    private final NativeImports imports;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.native-data.import-worker-delay-ms:1000}")
    public void advanceConfirmedImports() {
        java.util.List<Pending> pending;
        try {
            pending = jdbc.query(
                """
                SELECT job_id,account_id,actor_id,revision FROM (
                    SELECT job_id,account_id,actor_id,revision,updated_at,
                        ROW_NUMBER() OVER (PARTITION BY account_id ORDER BY updated_at,job_id) AS account_rank
                    FROM cfg_import_progress WHERE status='RUNNING'
                      AND (worker_retry_after IS NULL OR worker_retry_after<=?)
                ) pending WHERE account_rank=1 ORDER BY updated_at,job_id LIMIT 20
                """,
                (row, number) ->
                    new Pending(
                        row.getObject("job_id", UUID.class),
                        row.getObject("account_id", UUID.class),
                        row.getObject("actor_id", UUID.class),
                        row.getLong("revision")
                    ),
                clock.instant().atOffset(ZoneOffset.UTC)
            );
        } catch (RuntimeException unavailable) {
            log.warn("nativeImportWorker outcome=OWNER_STORE_UNAVAILABLE");
            return;
        }
        for (var job : pending) {
            try {
                imports.advance(job.account(), job.actor(), job.id(), job.revision());
            } catch (VclException held) {
                defer(job, held.code());
            } catch (RuntimeException unavailable) {
                defer(job, "OWNER_STORE_UNAVAILABLE");
            }
        }
    }

    private void defer(Pending job, String code) {
        log.warn("nativeImportWorker jobId={} outcome={}", job.id(), code);
        try {
            jdbc.update(
                """
                UPDATE cfg_import_progress SET worker_retry_after=?
                WHERE job_id=? AND account_id=? AND actor_id=? AND revision=? AND status='RUNNING'
                """,
                clock.instant().plusSeconds(15).atOffset(ZoneOffset.UTC),
                job.id(),
                job.account(),
                job.actor(),
                job.revision()
            );
        } catch (RuntimeException unavailable) {
            log.warn("nativeImportWorker jobId={} outcome=RETRY_SCHEDULE_UNAVAILABLE", job.id());
        }
    }
}
