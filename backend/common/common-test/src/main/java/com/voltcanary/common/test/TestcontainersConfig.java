package com.voltcanary.common.test;

import org.testcontainers.utility.DockerImageName;

public final class TestcontainersConfig {

    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");
    public static final DockerImageName KAFKA_IMAGE = DockerImageName
            .parse("confluentinc/cp-kafka:7.6.1")
            .asCompatibleSubstituteFor("confluentinc/cp-kafka");

    private TestcontainersConfig() {
    }
}
