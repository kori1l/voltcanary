package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.AnalyticsDlqProperties;
import com.voltcanary.analytics.config.AnalyticsKafkaProperties;
import com.voltcanary.analytics.producer.AnalyticsDeadLetterPublisher;
import com.voltcanary.analytics.producer.AnalyticsDeadLetterRecovery;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AnalyticsDeadLetterRecoveryTest {

    @Test
    void recoveryCannotAcknowledgeAnAsynchronousBrokerFailure() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var properties = new AnalyticsKafkaProperties();
        properties.setPublishEnabled(true);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(
            CompletableFuture.<SendResult<String, String>>failedFuture(
                new IllegalStateException("Broker failed")
            )
        );
        var recovery = new AnalyticsDeadLetterRecovery(
            new AnalyticsDeadLetterPublisher(
                kafka,
                new ObjectMapper(),
                new AnalyticsDlqProperties(),
                properties
            )
        );
        assertThatThrownBy(() ->
            recovery.accept(
                new ConsumerRecord<>("telemetry.validated.v1", 2, 17, "key", "payload"),
                new IllegalArgumentException()
            )
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("acknowledgement unavailable");
    }

    @Test
    void recoveryUsesTheOriginalOffsetAsStableIdentity() {
        var publisher = mock(AnalyticsDeadLetterPublisher.class);
        var record = new ConsumerRecord<>("telemetry.validated.v1", 2, 17, "key", "payload");
        new AnalyticsDeadLetterRecovery(publisher).accept(
            record,
            new IllegalArgumentException("private detail")
        );
        verify(publisher).publish(
            "telemetry.validated.v1:2:17",
            "telemetry.validated.v1",
            "payload",
            "IllegalArgumentException"
        );
    }
}
