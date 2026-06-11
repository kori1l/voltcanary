package com.voltcanary.alert.service;

import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsAlertEventHandler {

    private static final String ANOMALY_TOPIC = "anomaly.detected.v1";
    private static final String RISK_TOPIC = "risk-score.changed.v1";

    private final ProcessedEventService processedEventService;
    private final AlertService alertService;

    public AnalyticsAlertEventHandler(
            ProcessedEventService processedEventService,
            AlertService alertService) {
        this.processedEventService = processedEventService;
        this.alertService = alertService;
    }

    @Transactional
    public boolean handleAnomaly(AnomalyDetectedEvent event) {
        if (!processedEventService.markIfNew(ANOMALY_TOPIC, event.eventId())) {
            return false;
        }
        alertService.handleAnomaly(event);
        return true;
    }

    @Transactional
    public boolean handleRiskScoreChanged(RiskScoreChangedEvent event) {
        if (!processedEventService.markIfNew(RISK_TOPIC, event.eventId())) {
            return false;
        }
        alertService.handleRiskScoreChanged(event);
        return true;
    }
}
