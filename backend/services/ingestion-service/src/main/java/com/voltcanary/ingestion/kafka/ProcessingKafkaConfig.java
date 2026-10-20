package com.voltcanary.ingestion.kafka;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

@Configuration
public class ProcessingKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> processingKafkaListenerContainerFactory(KafkaProperties kafka,
            org.springframework.kafka.core.KafkaTemplate<String, String> processingDeadLetterTemplate) {
        Map<String, Object> properties = kafka.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        var recoverer = new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(processingDeadLetterTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition("source.processing-config.dlq.v1", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var errors = new org.springframework.kafka.listener.DefaultErrorHandler(recoverer, new org.springframework.util.backoff.FixedBackOff(1000, 2));
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

    @Bean
    org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> processingDeadLetterProducerFactory(KafkaProperties kafka) {
        var properties = kafka.buildProducerProperties(null);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        return new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    org.springframework.kafka.core.KafkaTemplate<String, String> processingDeadLetterTemplate(
            org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> processingDeadLetterProducerFactory) {
        return new org.springframework.kafka.core.KafkaTemplate<>(processingDeadLetterProducerFactory);
    }
}
