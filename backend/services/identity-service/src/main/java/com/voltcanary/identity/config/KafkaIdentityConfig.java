package com.voltcanary.identity.config;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaIdentityConfig {

    @Bean
    ProducerFactory<String, String> identityStringProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, String> identityStringKafkaTemplate(
            @Qualifier("identityStringProducerFactory") ProducerFactory<String, String> identityStringProducerFactory) {
        return new KafkaTemplate<>(identityStringProducerFactory);
    }
}
