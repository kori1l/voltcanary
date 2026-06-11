package com.voltcanary.telemetry.config;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.voltcanary.telemetry.event.TelemetryDeadLetterEvent;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class KafkaTelemetryConfig {

    @Bean
    ConsumerFactory<String, TelemetryReceivedEvent> telemetryConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TelemetryReceivedEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.telemetry");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TelemetryReceivedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, TelemetryReceivedEvent> telemetryConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, TelemetryReceivedEvent>();
        factory.setConsumerFactory(telemetryConsumerFactory);
        return factory;
    }

    @Bean
    ProducerFactory<String, TelemetryValidatedEvent> telemetryValidatedProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, TelemetryValidatedEvent> telemetryValidatedKafkaTemplate(
            ProducerFactory<String, TelemetryValidatedEvent> telemetryValidatedProducerFactory) {
        return new KafkaTemplate<>(telemetryValidatedProducerFactory);
    }

    @Bean
    ProducerFactory<String, TelemetryDeadLetterEvent> telemetryDeadLetterProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, TelemetryDeadLetterEvent> telemetryDeadLetterKafkaTemplate(
            ProducerFactory<String, TelemetryDeadLetterEvent> telemetryDeadLetterProducerFactory) {
        return new KafkaTemplate<>(telemetryDeadLetterProducerFactory);
    }

    @Bean
    ProducerFactory<String, String> telemetryStringProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, String> telemetryStringKafkaTemplate(
            ProducerFactory<String, String> telemetryStringProducerFactory) {
        return new KafkaTemplate<>(telemetryStringProducerFactory);
    }
}
