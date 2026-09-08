package com.voltcanary.alert.notification.application;

import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import com.voltcanary.alert.notification.port.AlertNotificationPort;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import com.voltcanary.alert.notification.repository.NotificationChannelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationDeliveryService implements AlertNotificationPort {

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

    @Override
    public void alertCreated(AlertNotificationRequest request) {
        dispatch(request);
    }

    @Override
    public void alertEscalated(AlertNotificationRequest request) {
        dispatch(request);
    }

    private void dispatch(AlertNotificationRequest request) {
        if (!properties.isEnabled() || request == null || request.accountId() == null) {
            return;
        }
        channelRepository.findByAccountIdAndEnabledTrueOrderByCreatedAtDesc(request.accountId())
                .forEach(channel -> attemptService.createPendingAttempt(request, channel));
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
