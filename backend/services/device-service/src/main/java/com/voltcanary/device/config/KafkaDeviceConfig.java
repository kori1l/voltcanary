package com.voltcanary.device.config;

import com.voltcanary.common.events.device.DeviceRegistryChangedEvent;
import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;


@Configuration
public class KafkaDeviceConfig {

    @Bean
    ConsumerFactory<String, IdentityAccountLifecycleEvent> identityAccountLifecycleConsumerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, IdentityAccountLifecycleEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.voltcanary.common.events.identity");
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, IdentityAccountLifecycleEvent>
            identityAccountLifecycleKafkaListenerContainerFactory(
                    ConsumerFactory<String, IdentityAccountLifecycleEvent> identityAccountLifecycleConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, IdentityAccountLifecycleEvent>();
        factory.setConsumerFactory(identityAccountLifecycleConsumerFactory);
        return factory;
    }

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

    @Bean
    ProducerFactory<String, String> deviceStringProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, String> deviceStringKafkaTemplate(
            @Qualifier("deviceStringProducerFactory") ProducerFactory<String, String> deviceStringProducerFactory) {
        return new KafkaTemplate<>(deviceStringProducerFactory);
    }
}
