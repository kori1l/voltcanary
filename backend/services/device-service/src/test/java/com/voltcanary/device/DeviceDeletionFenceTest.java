package com.voltcanary.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.common.web.JdbcDeviceFence;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class DeviceDeletionFenceTest {

    @Test
    void deletionWaitsForInflightWriterAndCannotBeReopenedByReplay() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
            "sa",
            ""
        );
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
            "create table device_deletion_fences(device_id uuid primary key,closed boolean not null)"
        );
        var fence = new JdbcDeviceFence(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UUID device = UUID.randomUUID();
        assertThatThrownBy(() -> fence.writable(device)).isInstanceOf(IllegalStateException.class);
        CountDownLatch writing = new CountDownLatch(1),
            release = new CountDownLatch(1),
            closing = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var writer = workers.submit(() ->
                tx.executeWithoutResult(status -> {
                    assertThat(fence.writable(device)).isTrue();
                    writing.countDown();
                    try {
                        assertThat(release.await(4, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        throw new RuntimeException(interrupted);
                    }
                })
            );
            assertThat(writing.await(3, TimeUnit.SECONDS)).isTrue();
            var deletion = workers.submit(() -> {
                closing.countDown();
                tx.executeWithoutResult(status -> fence.close(device));
            });
            assertThat(closing.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(
                java.util.concurrent.TimeoutException.class
            );
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> assertThat(fence.writable(device)).isFalse());
            tx.executeWithoutResult(status -> fence.close(device));
            tx.executeWithoutResult(status -> assertThat(fence.writable(device)).isFalse());
        }
    }
}
