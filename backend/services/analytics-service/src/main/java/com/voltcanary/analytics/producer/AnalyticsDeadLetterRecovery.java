package com.voltcanary.analytics.producer;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

/** Returning from recovery means the broker acknowledged the original record's DLQ copy. */
@Component
@RequiredArgsConstructor
public class AnalyticsDeadLetterRecovery implements ConsumerRecordRecoverer {

    private final AnalyticsDeadLetterPublisher publisher;

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        publisher.publish(
            record.topic() + ":" + record.partition() + ":" + record.offset(),
            record.topic(),
            record.value() == null ? null : String.valueOf(record.value()),
            failure.getClass().getSimpleName()
        );
    }
}
