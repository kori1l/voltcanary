package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Idempotency;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class DashboardIdempotency {
    private final DashboardStorage storage;
    private final ObjectMapper mapper;

    public DashboardIdempotency(DashboardStorage storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    /** Call within the use case transaction, after current actor authorization. */
    public <T> T execute(DashboardActor actor, String route, String key, JsonNode body,
            Class<T> type, Consumer<T> authorizeReplay, Supplier<T> operation) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new DashboardFailure("PRECONDITION_REQUIRED", "A safe Idempotency-Key of 1–128 characters is required");
        }
        storage.lockActor(actor);
        String keyHash = hash(key);
        String bodyHash = hash(body.toString());
        Instant now = Instant.now();
        var previous = storage.idempotency(actor, route, keyHash, now);
        if (previous.isPresent()) {
            if (!previous.get().bodyHash().equals(bodyHash)) {
                throw new DashboardFailure("CONFLICT", "Idempotency key was already used with a different body");
            }
            T outcome = mapper.convertValue(previous.get().outcome(), type);
            authorizeReplay.accept(outcome);
            return outcome;
        }
        T result = operation.get();
        storage.saveIdempotency(actor, route, keyHash,
                new Idempotency(bodyHash, mapper.valueToTree(result), now.plusSeconds(86400)));
        return result;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
