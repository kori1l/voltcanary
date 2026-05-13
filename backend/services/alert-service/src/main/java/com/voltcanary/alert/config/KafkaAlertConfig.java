package com.voltcanary.alert.config;

import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

@Configuration
public class KafkaAlertConfig {

    @Bean
    ConsumerFactory<String, AnomalyDetectedEvent> anomalyDetectedConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AnomalyDetectedEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.analytics");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, AnomalyDetectedEvent> anomalyDetectedKafkaListenerContainerFactory(
            ConsumerFactory<String, AnomalyDetectedEvent> anomalyDetectedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, AnomalyDetectedEvent>();
        factory.setConsumerFactory(anomalyDetectedConsumerFactory);
        return factory;
    }

    @Bean
    ConsumerFactory<String, RiskScoreChangedEvent> riskScoreChangedConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, RiskScoreChangedEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.analytics");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, RiskScoreChangedEvent> riskScoreChangedKafkaListenerContainerFactory(
            ConsumerFactory<String, RiskScoreChangedEvent> riskScoreChangedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RiskScoreChangedEvent>();
        factory.setConsumerFactory(riskScoreChangedConsumerFactory);
        return factory;
    }
}
