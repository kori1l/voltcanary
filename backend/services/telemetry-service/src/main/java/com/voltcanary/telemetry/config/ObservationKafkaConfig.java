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
public class ObservationKafkaConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<
        String,
        String
    > observationSchemaKafkaListenerContainerFactory(KafkaProperties kafka) {
        Map<String, Object> properties = kafka.buildConsumerProperties();
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        // Corrupt/missing schema must hold its partition for intervention, never silently advance readiness.
        factory.setCommonErrorHandler(
            new org.springframework.kafka.listener.DefaultErrorHandler(
                new org.springframework.util.backoff.FixedBackOff(
                    5000,
                    org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS
                )
            )
        );
        return factory;
    }
}
