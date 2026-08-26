package com.voltcanary.ingestion.connector;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisConnectorLeaseManager implements ConnectorLeaseManager {

    private static final String KEY_PREFIX = "voltcanary:ingestion:connector-lease:";
    private static final String FENCING_KEY_PREFIX = "voltcanary:ingestion:connector-lease-generation:";

    private final StringRedisTemplate redisTemplate;
    private final ConnectorRuntimeProperties properties;

    public RedisConnectorLeaseManager(StringRedisTemplate redisTemplate, ConnectorRuntimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Optional<ConnectorLease> acquire(UUID dataSourceId) {
        String key = key(dataSourceId);
        String existing = redisTemplate.opsForValue().get(key);
        ConnectorLease existingLease = parse(dataSourceId, existing);
        if (isOwnedByThisInstance(existingLease)) {
            return renew(existingLease);
        }
        Long fencingToken = redisTemplate.opsForValue().increment(fencingKey(dataSourceId));
        ConnectorLease lease = new ConnectorLease(dataSourceId, properties.getInstanceId(), fencingToken);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, payload(lease), properties.getLeaseTtl());
        return Boolean.TRUE.equals(acquired) && isCurrent(lease) ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public Optional<ConnectorLease> renew(ConnectorLease lease) {
        if (!isCurrent(lease)) {
            return Optional.empty();
        }
        redisTemplate.opsForValue().set(key(lease.dataSourceId()), payload(lease), properties.getLeaseTtl());
        return isCurrent(lease) ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public void release(ConnectorLease lease) {
        String key = key(lease.dataSourceId());
        if (Objects.equals(redisTemplate.opsForValue().get(key), payload(lease))) {
            redisTemplate.delete(key);
        }
    }

    @Override
    public boolean isCurrent(ConnectorLease lease) {
        return Objects.equals(redisTemplate.opsForValue().get(key(lease.dataSourceId())), payload(lease));
    }

    @Override
    public Optional<ConnectorLease> current(UUID dataSourceId) {
        ConnectorLease lease = parse(dataSourceId, redisTemplate.opsForValue().get(key(dataSourceId)));
        return isOwnedByThisInstance(lease) ? Optional.of(lease) : Optional.empty();
    }

    private String key(UUID dataSourceId) {
        return KEY_PREFIX + dataSourceId;
    }

    private String fencingKey(UUID dataSourceId) {
        return FENCING_KEY_PREFIX + dataSourceId;
    }

    private String payload(ConnectorLease lease) {
        return lease.ownerId() + ":" + lease.fencingToken();
    }

    private ConnectorLease parse(UUID dataSourceId, String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        int separator = payload.lastIndexOf(':');
        if (separator <= 0 || separator == payload.length() - 1) {
            return null;
        }
        try {
            return new ConnectorLease(dataSourceId, payload.substring(0, separator), Long.parseLong(payload.substring(separator + 1)));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isOwnedByThisInstance(ConnectorLease lease) {
        return lease != null && Objects.equals(lease.ownerId(), properties.getInstanceId());
    }
}
