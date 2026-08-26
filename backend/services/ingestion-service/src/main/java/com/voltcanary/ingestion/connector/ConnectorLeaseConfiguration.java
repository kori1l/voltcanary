package com.voltcanary.ingestion.connector;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ConnectorLeaseConfiguration {

    @Bean
    public ConnectorLeaseManager connectorLeaseManager(
            ConnectorRuntimeProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplate) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template != null) {
            return new RedisConnectorLeaseManager(template, properties);
        }
        return new InMemoryConnectorLeaseManager(properties, Clock.systemUTC());
    }
}
