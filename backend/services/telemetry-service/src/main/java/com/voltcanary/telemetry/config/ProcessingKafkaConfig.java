package com.voltcanary.telemetry.config;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

@Configuration
public class ProcessingKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> processingKafkaListenerContainerFactory(KafkaProperties kafka,
            @org.springframework.beans.factory.annotation.Qualifier("telemetryStringKafkaTemplate")
            org.springframework.kafka.core.KafkaTemplate<String, String> template) {
        Map<String, Object> properties = kafka.buildConsumerProperties();
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        var recoverer = new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(template,
                (record, exception) -> new org.apache.kafka.common.TopicPartition("source.processing-config.dlq.v1", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var errors = new org.springframework.kafka.listener.DefaultErrorHandler(recoverer,
                new org.springframework.util.backoff.FixedBackOff(1000, 2));
        errors.setBackOffFunction((record, exception) -> {
            Throwable cause = exception;
            while (cause.getCause() != null) cause = cause.getCause();
            return cause instanceof IllegalArgumentException || cause instanceof com.fasterxml.jackson.core.JsonProcessingException
                    ? new org.springframework.util.backoff.FixedBackOff(1000, 2)
                    : new org.springframework.util.backoff.FixedBackOff(5000, org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS);
        });
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
