package com.voltcanary.device.config;

import com.voltcanary.common.events.device.DeviceRegistryChangedEvent;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class KafkaDeviceConfig {

    @Bean
    ProducerFactory<String, DeviceRegistryChangedEvent> deviceRegistryChangedProducerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, DeviceRegistryChangedEvent> deviceRegistryChangedKafkaTemplate(
            ProducerFactory<String, DeviceRegistryChangedEvent> deviceRegistryChangedProducerFactory) {
        return new KafkaTemplate<>(deviceRegistryChangedProducerFactory);
    }
}
