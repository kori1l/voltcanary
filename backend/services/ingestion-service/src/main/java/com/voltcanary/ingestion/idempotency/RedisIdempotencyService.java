package com.voltcanary.ingestion.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.idempotency.redis-enabled", havingValue = "true")
class RedisIdempotencyService implements IdempotencyService {

    static final DefaultRedisScript<List> RESERVE = new DefaultRedisScript<>(
        """
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        if redis.call('EXISTS', KEYS[1]) == 0 then
            redis.call('HSET', KEYS[1], 'payload', ARGV[1], 'status', 'PENDING', 'owner', ARGV[2], 'until', now + ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return {'ACQUIRED', ARGV[1]}
        end
        if redis.call('HGET', KEYS[1], 'status') == 'PUBLISHED' then return {'PUBLISHED', ARGV[1]} end
        local payload = redis.call('HGET', KEYS[1], 'payload')
        if tonumber(redis.call('HGET', KEYS[1], 'until')) > now then return {'IN_FLIGHT', payload} end
        redis.call('HSET', KEYS[1], 'owner', ARGV[2], 'until', now + ARGV[3])
        redis.call('PEXPIRE', KEYS[1], ARGV[4])
        return {'ACQUIRED', payload}
        """,
        List.class
    );
    static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>(
        """
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1]
            or redis.call('HGET', KEYS[1], 'status') ~= 'PENDING'
            or tonumber(redis.call('HGET', KEYS[1], 'until')) <= now then return 0 end
        redis.call('HSET', KEYS[1], 'status', 'PUBLISHED')
        redis.call('HDEL', KEYS[1], 'payload', 'owner', 'until')
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return 1
        """,
        Long.class
    );
    static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
        """
        if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1]
            or redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then return 0 end
        redis.call('HSET', KEYS[1], 'until', 0)
        return 1
        """,
        Long.class
    );

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration retention;
    private final Duration lease;
    private final MeterRegistry meters;

    RedisIdempotencyService(
        StringRedisTemplate redis,
        ObjectMapper mapper,
        IngestionProperties properties,
        MeterRegistry meters
    ) {
        this.redis = redis;
        this.mapper = mapper
            .copy()
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS
            )
            .setNodeFactory(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)
            );
        this.retention = properties.getIdempotencyTtl();
        this.lease = properties.getIdempotencyLease();
        DeliveryRetention.validate(retention, lease, properties.getKafkaAckTimeout());
        this.meters = meters;
    }

    @Override
    public Reservation reserve(TelemetryReceivedEvent candidate) {
        return measured(() -> {
            String key = TelemetryIdentity.key(mapper, candidate);
            UUID owner = UUID.randomUUID();
            try {
                var result = redis.execute(
                    RESERVE,
                    List.of(key),
                    mapper.writeValueAsString(candidate),
                    owner.toString(),
                    Long.toString(lease.toMillis()),
                    Long.toString(retention.toMillis())
                );
                if (result == null || result.size() != 2) throw new IllegalStateException(
                    "Delivery reservation unavailable"
                );
                var retained = mapper.readValue(
                    (String) result.get(1),
                    TelemetryReceivedEvent.class
                );
                if (!key.equals(TelemetryIdentity.key(mapper, retained))) {
                    throw new IllegalStateException("Retained telemetry identity changed");
                }
                return new Reservation(
                    key,
                    owner,
                    Status.valueOf((String) result.get(0)),
                    retained
                );
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Invalid retained telemetry envelope", failure);
            }
        });
    }

    @Override
    public void complete(Reservation reservation) {
        measured(() -> {
            Long completed = redis.execute(
                COMPLETE,
                List.of(reservation.key()),
                reservation.owner().toString(),
                Long.toString(retention.toMillis())
            );
            if (!Long.valueOf(1).equals(completed)) throw new IllegalStateException(
                "Delivery reservation expired or changed"
            );
            return completed;
        });
    }

    @Override
    public void release(Reservation reservation) {
        measured(() ->
            redis.execute(RELEASE, List.of(reservation.key()), reservation.owner().toString())
        );
    }

    private <T> T measured(Supplier<T> operation) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            outcome = "error";
            meters
                .counter(
                    "voltcanary.ingestion.redis.idempotency.errors",
                    "reason",
                    failure.getClass().getSimpleName()
                )
                .increment();
            throw new Unavailable(failure);
        } finally {
            sample.stop(
                meters.timer("voltcanary.ingestion.redis.idempotency.latency", "outcome", outcome)
            );
        }
    }
}
