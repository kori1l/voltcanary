package com.voltcanary.telemetry.observation;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded stored-input recovery; schema arrival never authorizes a new source or substitutes a revision. */
@Component
@RequiredArgsConstructor
public class ObservationReconciliation {
    private final JdbcTemplate jdbc;
    private final ObservationCapture capture;
    private final ObservationCaptureProperties properties;

    @Scheduled(fixedDelayString = "${app.observations.reconcile-delay:5s}", initialDelay = 5000)
    public void reconcile() {
        if (!properties.isEnabled()) return;
        for (UUID id : jdbc.queryForList("SELECT event_id FROM cfg_observation_input WHERE status='PENDING' AND retry_after<=CURRENT_TIMESTAMP ORDER BY retry_after,event_id LIMIT 20", UUID.class)) {
            try { capture.retry(id); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Observation recovery held eventId={} code={}", id,
                        failure instanceof ObservationCaptureUnavailable ? failure.getMessage() : "OBSERVATION_STORAGE_UNAVAILABLE");
            }
            finally {
                jdbc.update("UPDATE cfg_observation_input SET retry_after=? WHERE event_id=? AND status='PENDING'",
                        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(5)), id);
            }
        }
    }
}
