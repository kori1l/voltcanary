package com.voltcanary.alert.service;

import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxEntry;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsAlertEventHandler {

    public static final String ANOMALY_TOPIC = "anomaly.detected.v1";
    public static final String RISK_TOPIC = "risk-score.changed.v1";
    public static final String ANOMALY_EVENT_TYPE = "ANOMALY_DETECTED";
    public static final String RISK_EVENT_TYPE = "RISK_SCORE_CHANGED";

    private final ProcessedEventService processedEventService;
    private final AlertAnalyticsEventInboxService inboxService;
    private final AlertService alertService;

    public AnalyticsAlertEventHandler(
            ProcessedEventService processedEventService,
            AlertAnalyticsEventInboxService inboxService,
            AlertService alertService) {
        this.processedEventService = processedEventService;
        this.inboxService = inboxService;
        this.alertService = alertService;
    }

    public boolean handleAnomaly(AnomalyDetectedEvent event) {
        AlertAnalyticsEventInboxEntry entry = inboxService.recordReceived(
                ANOMALY_TOPIC,
                event.eventId(),
                ANOMALY_EVENT_TYPE,
                event);
        return replayAnomaly(entry.getId(), event);
    }

    public boolean handleRiskScoreChanged(RiskScoreChangedEvent event) {
        AlertAnalyticsEventInboxEntry entry = inboxService.recordReceived(
                RISK_TOPIC,
                event.eventId(),
                RISK_EVENT_TYPE,
                event);
        return replayRiskScoreChanged(entry.getId(), event);
    }

    public boolean replayAnomaly(java.util.UUID inboxEntryId, AnomalyDetectedEvent event) {
        inboxService.markProcessing(inboxEntryId);
        try {
            Alert alert = alertService.handleAnomaly(event);
            boolean firstProcessing = processedEventService.markIfNew(ANOMALY_TOPIC, event.eventId());
            inboxService.markProcessed(inboxEntryId, alert);
            return firstProcessing;
        } catch (RuntimeException exception) {
            inboxService.markFailed(inboxEntryId, exception);
            throw exception;
        }
    }

    public boolean replayRiskScoreChanged(java.util.UUID inboxEntryId, RiskScoreChangedEvent event) {
        inboxService.markProcessing(inboxEntryId);
        try {
            Alert alert = alertService.handleRiskScoreChanged(event);
            boolean firstProcessing = processedEventService.markIfNew(RISK_TOPIC, event.eventId());
            inboxService.markProcessed(inboxEntryId, alert);
            return firstProcessing;
        } catch (RuntimeException exception) {
            inboxService.markFailed(inboxEntryId, exception);
            throw exception;
        }
    }
}
