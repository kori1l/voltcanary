package com.voltcanary.ingestion.connector;

import java.util.Objects;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisConnectorLeaseManager implements ConnectorLeaseManager {

    private static final String KEY_PREFIX = "voltcanary:ingestion:connector-lease:";

    private final StringRedisTemplate redisTemplate;
    private final ConnectorRuntimeProperties properties;

    public RedisConnectorLeaseManager(StringRedisTemplate redisTemplate, ConnectorRuntimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean acquire(UUID dataSourceId) {
        String key = key(dataSourceId);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, properties.getInstanceId(), properties.getLeaseTtl());
        return Boolean.TRUE.equals(acquired) || renew(dataSourceId);
    }

    @Override
    public boolean renew(UUID dataSourceId) {
        String key = key(dataSourceId);
        String owner = redisTemplate.opsForValue().get(key);
        if (!Objects.equals(owner, properties.getInstanceId())) {
            return false;
        }
        redisTemplate.opsForValue().set(key, properties.getInstanceId(), properties.getLeaseTtl());
        return true;
    }

    @Override
    public void release(UUID dataSourceId) {
        String key = key(dataSourceId);
        if (Objects.equals(redisTemplate.opsForValue().get(key), properties.getInstanceId())) {
            redisTemplate.delete(key);
        }
    }

    @Override
    public boolean isOwned(UUID dataSourceId) {
        return Objects.equals(redisTemplate.opsForValue().get(key(dataSourceId)), properties.getInstanceId());
    }

    private String key(UUID dataSourceId) {
        return KEY_PREFIX + dataSourceId;
    }
}
