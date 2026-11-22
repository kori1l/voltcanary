package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voltcanary.telemetry.producer.TelemetryOutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxLeaseBoundaryTest {

    @Test
    void lateAcknowledgementCannotCompleteAnotherPublishersClaim() {
        verifyStaleResult(false);
    }

    @Test
    void lateFailureCannotRequeueAnotherPublishersClaim() {
        verifyStaleResult(true);
    }

    @SuppressWarnings("unchecked")
    private void verifyStaleResult(boolean failure) {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
            "create table telemetry_event_outbox(id uuid primary key,event_key varchar,topic varchar,message_key varchar,payload_json varchar,status varchar,attempts int,created_at timestamp,locked_at timestamp,locked_by varchar,published_at timestamp,next_attempt_at timestamp,last_error varchar)"
        );
        UUID id = UUID.randomUUID();
        jdbc.update(
            "insert into telemetry_event_outbox(id,event_key,topic,message_key,payload_json,status,attempts,created_at) values(?,?,?,?,?,'PENDING',0,?)",
            id,
            "event",
            "topic",
            "key",
            "{}",
            Timestamp.from(Instant.now())
        );
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            jdbc.update(
                "update telemetry_event_outbox set locked_by='replacement',attempts=2 where id=?",
                id
            );
            return failure
                ? CompletableFuture.failedFuture(new IllegalStateException("late error"))
                : CompletableFuture.completedFuture(null);
        });
        var publisher = new TelemetryOutboxPublisher(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            kafka,
            new SimpleMeterRegistry()
        );
        publisher.publishPending();
        assertThat(
            jdbc.queryForObject(
                "select status from telemetry_event_outbox where id=?",
                String.class,
                id
            )
        ).isEqualTo("PROCESSING");
        assertThat(
            jdbc.queryForObject(
                "select locked_by from telemetry_event_outbox where id=?",
                String.class,
                id
            )
        ).isEqualTo("replacement");
        assertThat(
            jdbc.queryForObject(
                "select attempts from telemetry_event_outbox where id=?",
                Integer.class,
                id
            )
        ).isEqualTo(2);
    }
}
