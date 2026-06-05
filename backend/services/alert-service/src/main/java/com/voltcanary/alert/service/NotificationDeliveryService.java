package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertNotificationProperties;
import com.voltcanary.alert.domain.Alert;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationDeliveryService {

    private final AlertNotificationProperties properties;
    private final NotificationDeliveryWorker worker;

    public NotificationDeliveryService(AlertNotificationProperties properties, NotificationDeliveryWorker worker) {
        this.properties = properties;
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
        UUID alertId = alert.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    worker.deliverAlert(alertId, deliveryEventType);
                }
            });
            return;
        }
        worker.deliverAlert(alertId, deliveryEventType);
    }
}
