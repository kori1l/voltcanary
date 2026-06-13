package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.config.AlertReconciliationProperties;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxEntry;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxStatus;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AlertAnalyticsEventReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(AlertAnalyticsEventReconciliationService.class);
    private static final String LOCK_NAME = "alert-analytics-event-reconciliation";

    private final AlertReconciliationProperties properties;
    private final SchedulerLockService schedulerLockService;
    private final AlertAnalyticsEventInboxService inboxService;
    private final AnalyticsAlertEventHandler eventHandler;
    private final ObjectMapper objectMapper;

    public AlertAnalyticsEventReconciliationService(
            AlertReconciliationProperties properties,
            SchedulerLockService schedulerLockService,
            AlertAnalyticsEventInboxService inboxService,
            AnalyticsAlertEventHandler eventHandler,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.schedulerLockService = schedulerLockService;
        this.inboxService = inboxService;
        this.eventHandler = eventHandler;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "#{@alertReconciliationProperties.interval.toMillis()}")
    public void reconcileDueAnalyticsEvents() {
        if (!properties.isEnabled() || !schedulerLockService.acquire(LOCK_NAME, properties.getLockLease())) {
            return;
        }
        try {
            int reconciled = 0;
            for (AlertAnalyticsEventInboxEntry entry : inboxService.findDueForReconciliation()) {
                reconcile(entry);
                reconciled++;
            }
            if (reconciled > 0) {
                log.info("alertAnalyticsEventReconciliation reconciled={}", reconciled);
            }
        } finally {
            schedulerLockService.release(LOCK_NAME);
        }
    }

    public void reconcile(AlertAnalyticsEventInboxEntry entry) {
        if (entry.getStatus() == AlertAnalyticsEventInboxStatus.PROCESSED
                || entry.getStatus() == AlertAnalyticsEventInboxStatus.NO_ALERT) {
            return;
        }
        try {
            switch (entry.getEventType()) {
                case AnalyticsAlertEventHandler.ANOMALY_EVENT_TYPE ->
                        eventHandler.replayAnomaly(entry.getId(), objectMapper.readValue(entry.getPayloadJson(), AnomalyDetectedEvent.class));
                case AnalyticsAlertEventHandler.RISK_EVENT_TYPE ->
                        eventHandler.replayRiskScoreChanged(entry.getId(), objectMapper.readValue(entry.getPayloadJson(), RiskScoreChangedEvent.class));
                default -> throw new IllegalArgumentException("Unsupported analytics alert event type: " + entry.getEventType());
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid analytics alert inbox payload", exception);
        }
    }
}
