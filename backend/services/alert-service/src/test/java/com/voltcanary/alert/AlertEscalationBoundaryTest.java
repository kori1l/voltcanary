package com.voltcanary.alert;

import com.voltcanary.alert.notification.port.AlertNotificationPort;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import com.voltcanary.alert.service.AlertEscalationService;
import com.voltcanary.alert.service.AlertService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false", "app.kafka.publish-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:escalation-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.open-in-view=false", "spring.flyway.enabled=true",
        "app.alert.authorization.enabled=false", "app.alert.notifications.worker-enabled=false",
        "app.alert.reconciliation.enabled=false", "app.maintenance.reconciliation.enabled=false"
})
class AlertEscalationBoundaryTest {
    @Autowired private AlertService alerts;
    @Autowired private AlertEscalationService escalation;
    @Autowired private AlertEventRepository events;
    @MockitoBean private AlertNotificationPort notifications;
    @MockitoBean private SchedulerLockService locks;

    @Test
    void schedulerRollsBackEventWhenDeliveryEnqueueFailsAndNextScanCanRetry() {
        when(locks.acquire(any(), any())).thenReturn(true);
        var alert = alerts.handleAnomaly(new AnomalyDetectedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "POWER_SPIKE", "MEDIUM", 30, "Due alert", Instant.now().minusSeconds(7200)));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("delivery enqueue unavailable");
        }).when(notifications).alertEscalated(any());
        assertThatThrownBy(escalation::scheduledEscalationScan).isInstanceOf(IllegalStateException.class);
        assertThat(events.findByAlertIdOrderByCreatedAtDesc(alert.getId()))
                .noneSatisfy(event -> assertThat(event.getEventType()).isEqualTo("ESCALATED"));
        doNothing().when(notifications).alertEscalated(any());
        escalation.scheduledEscalationScan();
        escalation.scheduledEscalationScan();
        assertThat(events.findByAlertIdOrderByCreatedAtDesc(alert.getId()))
                .filteredOn(event -> "ESCALATED".equals(event.getEventType())).hasSize(1);
    }
}
