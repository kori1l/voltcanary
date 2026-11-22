package com.voltcanary.ingestion.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedConnectorTasksTest {

    @Test
    void saturatedIoQueueRejectsWorkWithoutConsumingHeartbeatCapacity() throws Exception {
        var properties = new ConnectorRuntimeProperties();
        properties.setWorkConcurrency(1);
        properties.setHeartbeatConcurrency(1);
        properties.setWorkQueueCapacity(1);
        var tasks = new BoundedConnectorTasks(properties);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var heartbeat = new CountDownLatch(1);
        try {
            assertThat(
                tasks.work(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                })
            ).isTrue();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.work(() -> {})).isTrue();
            assertThat(tasks.work(() -> {})).isFalse();
            assertThat(tasks.heartbeat(heartbeat::countDown)).isTrue();
            assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            tasks.close();
        }
        assertThat(tasks.work(() -> {})).isFalse();
        assertThat(tasks.heartbeat(() -> {})).isFalse();
    }
}
