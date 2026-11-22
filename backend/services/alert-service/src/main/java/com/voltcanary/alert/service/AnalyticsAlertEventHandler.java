package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxEntry;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsAlertEventHandler {

    public static final String ANOMALY_TOPIC = "anomaly.detected.v1";
    public static final String RISK_TOPIC = "risk-score.changed.v1";
    public static final String ANOMALY_EVENT_TYPE = "ANOMALY_DETECTED";
    public static final String RISK_EVENT_TYPE = "RISK_SCORE_CHANGED";

    private final ProcessedEventService processedEventService;
    private final AlertAnalyticsEventInboxService inboxService;
    private final AlertService alertService;
    private final VariableAnomalyAdmission variableAdmission;

    public boolean handleAnomaly(AnomalyDetectedEvent event) {
        AlertAnalyticsEventInboxEntry entry = inboxService.recordReceived(
                ANOMALY_TOPIC,
                event.eventId(),
                ANOMALY_EVENT_TYPE,
                event);
        return entry != null && replayAnomaly(entry.getId(), event);
    }

    public boolean handleRiskScoreChanged(RiskScoreChangedEvent event) {
        AlertAnalyticsEventInboxEntry entry = inboxService.recordReceived(
                RISK_TOPIC,
                event.eventId(),
                RISK_EVENT_TYPE,
                event);
        return entry != null && replayRiskScoreChanged(entry.getId(), event);
    }

    public boolean replayAnomaly(java.util.UUID inboxEntryId, AnomalyDetectedEvent event) {
        var claim = inboxService.markProcessing(inboxEntryId);
        if (claim == null) return false;
        try {
            Alert alert = variableAdmission.accepts(event) ? alertService.handleAnomaly(event) : null;
            boolean firstProcessing = processedEventService.markIfNew(ANOMALY_TOPIC, event.eventId());
            return inboxService.markProcessed(claim, alert) && firstProcessing;
        } catch (RuntimeException exception) {
            inboxService.markFailed(claim, exception);
            throw exception;
        }
    }

    public boolean replayRiskScoreChanged(java.util.UUID inboxEntryId, RiskScoreChangedEvent event) {
        var claim = inboxService.markProcessing(inboxEntryId);
        if (claim == null) return false;
        try {
            Alert alert = alertService.handleRiskScoreChanged(event);
            boolean firstProcessing = processedEventService.markIfNew(RISK_TOPIC, event.eventId());
            return inboxService.markProcessed(claim, alert) && firstProcessing;
        } catch (RuntimeException exception) {
            inboxService.markFailed(claim, exception);
            throw exception;
        }
    }
}
