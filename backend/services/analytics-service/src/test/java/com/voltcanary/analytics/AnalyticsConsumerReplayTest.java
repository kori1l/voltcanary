package com.voltcanary.analytics;

import com.voltcanary.analytics.service.AnalyticsJobService;
import com.voltcanary.analytics.service.TelemetryValidatedEventHandler;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.analytics.authorization.enabled=false",
        "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics-consumer-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AnalyticsConsumerReplayTest {

    @Autowired
    private TelemetryValidatedEventHandler eventHandler;

    @MockitoBean
    private AnalyticsJobService jobService;

    @Test
    void duplicateTelemetryValidatedEventEnqueuesOnlyOneAnalyticsJob() {
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-06-13T08:00:00Z");
        TelemetryValidatedEvent event = new TelemetryValidatedEvent(
                eventId,
                deviceId,
                "plug-001",
                timestamp,
                Map.of("activePowerW", 840.2),
                List.of(),
                timestamp.plusSeconds(1));

        boolean first = eventHandler.handle(event);
        boolean duplicate = eventHandler.handle(event);

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        verify(jobService, times(1)).enqueueRecalculation(eq(deviceId), eq(timestamp));
    }
}
