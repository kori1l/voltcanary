package com.voltcanary.ingestion.connector;

import java.util.Objects;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

@RequiredArgsConstructor
public class RedisConnectorLeaseManager implements ConnectorLeaseManager {

    private static final String KEY_PREFIX = "voltcanary:ingestion:connector-lease:";
    private static final String FENCING_KEY_PREFIX = "voltcanary:ingestion:connector-lease-generation:";

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('PEXPIRE',KEYS[1],ARGV[2]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ConnectorRuntimeProperties properties;

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
        Long renewed = redisTemplate.execute(RENEW, List.of(key(lease.dataSourceId())),
                payload(lease), Long.toString(properties.getLeaseTtl().toMillis()));
        return Long.valueOf(1).equals(renewed) ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public void release(ConnectorLease lease) {
        redisTemplate.execute(RELEASE, List.of(key(lease.dataSourceId())), payload(lease));
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
