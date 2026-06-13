package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertNotificationProperties;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.repository.NotificationChannelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationDeliveryService {

    private final AlertNotificationProperties properties;
    private final NotificationChannelRepository channelRepository;
    private final NotificationAttemptService attemptService;
    private final NotificationDeliveryWorker worker;

    public NotificationDeliveryService(
            AlertNotificationProperties properties,
            NotificationChannelRepository channelRepository,
            NotificationAttemptService attemptService,
            NotificationDeliveryWorker worker) {
        this.properties = properties;
        this.channelRepository = channelRepository;
        this.attemptService = attemptService;
        this.worker = worker;
    }

    public void dispatchAlertCreated(Alert alert) {
        dispatch(alert, "ALERT_CREATED");
    }

    public void dispatchAlertEscalated(Alert alert) {
        dispatch(alert, "ALERT_ESCALATED");
    }

    private void dispatch(Alert alert, String deliveryEventType) {
        if (!properties.isEnabled() || alert == null || alert.getAccountId() == null) {
            return;
        }
        channelRepository.findByAccountIdAndEnabledTrueOrderByCreatedAtDesc(alert.getAccountId())
                .forEach(channel -> attemptService.createPendingAttempt(alert, channel, deliveryEventType));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    worker.processAvailableAsync();
                }
            });
            return;
        }
        worker.processAvailableAsync();
    }
}
