package com.voltcanary.alert.config;

import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaAlertConfig {

    private static final String ANOMALY_TOPIC = "anomaly.detected.v1";
    private static final String RISK_SCORE_TOPIC = "risk-score.changed.v1";

    @Bean
    ConsumerFactory<String, AnomalyDetectedEvent> anomalyDetectedConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AnomalyDetectedEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.analytics");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, AnomalyDetectedEvent> anomalyDetectedKafkaListenerContainerFactory(
            ConsumerFactory<String, AnomalyDetectedEvent> anomalyDetectedConsumerFactory,
            DefaultErrorHandler alertKafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, AnomalyDetectedEvent>();
        factory.setConsumerFactory(anomalyDetectedConsumerFactory);
        factory.setCommonErrorHandler(alertKafkaErrorHandler);
        return factory;
    }

    @Bean
    ConsumerFactory<String, RiskScoreChangedEvent> riskScoreChangedConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, RiskScoreChangedEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.analytics");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, RiskScoreChangedEvent> riskScoreChangedKafkaListenerContainerFactory(
            ConsumerFactory<String, RiskScoreChangedEvent> riskScoreChangedConsumerFactory,
            DefaultErrorHandler alertKafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RiskScoreChangedEvent>();
        factory.setConsumerFactory(riskScoreChangedConsumerFactory);
        factory.setCommonErrorHandler(alertKafkaErrorHandler);
        return factory;
    }

    @Bean
    ProducerFactory<String, Object> alertDeadLetterProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, Object> alertDeadLetterKafkaTemplate(
            ProducerFactory<String, Object> alertDeadLetterProducerFactory) {
        return new KafkaTemplate<>(alertDeadLetterProducerFactory);
    }

    @Bean
    DeadLetterPublishingRecoverer alertDeadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> alertDeadLetterKafkaTemplate,
            AlertKafkaProperties alertKafkaProperties) {
        return new DeadLetterPublishingRecoverer(
                alertDeadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(dlqTopic(record.topic(), alertKafkaProperties), record.partition()));
    }

    @Bean
    DefaultErrorHandler alertKafkaErrorHandler(
            DeadLetterPublishingRecoverer alertDeadLetterPublishingRecoverer,
            AlertKafkaProperties alertKafkaProperties,
            MeterRegistry meterRegistry) {
        long retryAttempts = Math.max(0, alertKafkaProperties.getRetry().getMaxAttempts() - 1L);
        long retryInterval = Math.max(0, alertKafkaProperties.getRetry().getInterval().toMillis());
        var errorHandler = new DefaultErrorHandler(
                alertDeadLetterPublishingRecoverer,
                new FixedBackOff(retryInterval, retryAttempts));
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> meterRegistry.counter(
                "alert.kafka.consumer.failures",
                "topic", record.topic(),
                "exception", exception.getClass().getSimpleName(),
                "deliveryAttempt", Integer.toString(deliveryAttempt)).increment());
        return errorHandler;
    }

    private static String dlqTopic(String sourceTopic, AlertKafkaProperties alertKafkaProperties) {
        if (ANOMALY_TOPIC.equals(sourceTopic)) {
            return alertKafkaProperties.getDlq().getAnomalyTopic();
        }
        if (RISK_SCORE_TOPIC.equals(sourceTopic)) {
            return alertKafkaProperties.getDlq().getRiskScoreTopic();
        }
        return sourceTopic + ".dlq";
    }
}
