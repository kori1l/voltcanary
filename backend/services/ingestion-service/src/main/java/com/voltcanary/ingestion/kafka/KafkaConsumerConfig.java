package com.voltcanary.ingestion.kafka;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
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
public class KafkaConsumerConfig {

    @Bean
    ConsumerFactory<String, SourceRuntimeConfigEvent> sourceRuntimeConfigConsumerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, SourceRuntimeConfigEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.device");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, SourceRuntimeConfigEvent>
            sourceRuntimeConfigKafkaListenerContainerFactory(
                    ConsumerFactory<String, SourceRuntimeConfigEvent> sourceRuntimeConfigConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, SourceRuntimeConfigEvent>();
        factory.setConsumerFactory(sourceRuntimeConfigConsumerFactory);
        return factory;
    }
}
