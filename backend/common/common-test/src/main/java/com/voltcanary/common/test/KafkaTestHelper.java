package com.voltcanary.common.test;

import java.util.Map;
import org.testcontainers.containers.KafkaContainer;

public final class KafkaTestHelper {

    private KafkaTestHelper() {
    }

    public static KafkaContainer createKafkaContainer() {
        return new KafkaContainer(TestcontainersConfig.KAFKA_IMAGE);
    }

    public static Map<String, Object> producerProperties(KafkaContainer kafka) {
        return Map.of("bootstrap.servers", kafka.getBootstrapServers());
    }

    public static Map<String, Object> consumerProperties(KafkaContainer kafka, String groupId) {
        return Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", groupId,
                "auto.offset.reset", "earliest");
    }
}
