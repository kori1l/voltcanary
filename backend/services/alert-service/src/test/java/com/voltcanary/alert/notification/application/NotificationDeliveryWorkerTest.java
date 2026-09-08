package com.voltcanary.alert.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationDeliveryWorkerTest {

    @Test
    void retryDelayUsesBoundedExponentialBackoff() {
        AlertNotificationProperties properties = new AlertNotificationProperties();
        properties.setRetryInterval(Duration.ofSeconds(2));
        properties.setRetryMultiplier(3.0d);
        properties.setMaxRetryDelay(Duration.ofSeconds(10));
        NotificationDeliveryWorker worker = worker(properties);

        assertThat(worker.retryDelay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(worker.retryDelay(2)).isEqualTo(Duration.ofSeconds(6));
        assertThat(worker.retryDelay(3)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void retryDelayKeepsJitterInsideConfiguredBoundsAndCap() {
        AlertNotificationProperties properties = new AlertNotificationProperties();
        properties.setRetryInterval(Duration.ofSeconds(10));
        properties.setRetryMultiplier(2.0d);
        properties.setMaxRetryDelay(Duration.ofSeconds(30));
        properties.setRetryJitterPercent(25.0d);
        NotificationDeliveryWorker worker = worker(properties);

        for (int index = 0; index < 100; index++) {
            Duration delay = worker.retryDelay(2);

            assertThat(delay).isGreaterThanOrEqualTo(Duration.ofSeconds(15));
            assertThat(delay).isLessThanOrEqualTo(Duration.ofSeconds(25));
        }
    }

    private NotificationDeliveryWorker worker(AlertNotificationProperties properties) {
        return new NotificationDeliveryWorker(
                null,
                null,
                null,
                null,
                properties,
                new ObjectMapper(),
                null,
                new SimpleMeterRegistry());
    }
}
