package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Opaque signed LIVE seek position; expiry is absolute and never extended by pagination. */
public final class NativeQueryCursor {

    public record Position(
        UUID recordId,
        long initialDataVersion,
        boolean drifted,
        Instant expiresAt
    ) {}

    private final byte[] key;
    private final Clock clock;
    private final VclParser parser = new VclParser();

    public NativeQueryCursor(byte[] key, Clock clock) {
        if (key.length < 32) throw new IllegalArgumentException(
            "Cursor signing key must contain at least 32 bytes"
        );
        this.key = key.clone();
        this.clock = clock;
    }

    public Position first(NativeDataset dataset) {
        return new Position(
            null,
            dataset.dataVersion(),
            false,
            clock.instant().plus(Duration.ofMinutes(10))
        );
    }

    Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public String encode(
        UUID actor,
        NativeDataset dataset,
        String scope,
        String queryDigest,
        Position position
    ) {
        var json = JsonNodeFactory.instance
            .objectNode()
            .put("version", "native-live-cursor-1")
            .put("accountId", dataset.accountId().toString())
            .put("actorId", actor.toString())
            .put("datasetId", dataset.schema().id().toString())
            .put("schemaRevision", dataset.schema().revision())
            .put("generation", dataset.generation().toString())
            .put("scope", scope)
            .put("queryDigest", queryDigest)
            .put("recordId", position.recordId().toString())
            .put("initialDataVersion", position.initialDataVersion())
            .put("drifted", position.drifted())
            .put("expiresAt", position.expiresAt().toString());
        byte[] bytes = VclCanonical.encode(json).getBytes(StandardCharsets.UTF_8);
        return (
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) +
            "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign(bytes))
        );
    }

    public Position decode(
        String cursor,
        UUID actor,
        NativeDataset dataset,
        String scope,
        String digest
    ) {
        try {
            if (cursor.length() > 4096) throw invalid();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(parts[0]);
            if (
                !MessageDigest.isEqual(sign(bytes), Base64.getUrlDecoder().decode(parts[1]))
            ) throw invalid();
            var json = parser.readJson(bytes);
            if (
                !json.isObject() ||
                json.size() != 12 ||
                !json.path("version").asText().equals("native-live-cursor-1")
            ) throw invalid();
            if (
                !json.path("accountId").asText().equals(dataset.accountId().toString()) ||
                !json.path("actorId").asText().equals(actor.toString()) ||
                !json.path("scope").asText().equals(scope)
            ) throw new VclException("CURSOR_SCOPE_CHANGED", "");
            if (
                !json.path("datasetId").asText().equals(dataset.schema().id().toString()) ||
                json.path("schemaRevision").asLong() != dataset.schema().revision() ||
                !json.path("generation").asText().equals(dataset.generation().toString()) ||
                !json.path("queryDigest").asText().equals(digest)
            ) {
                throw new VclException("CURSOR_CONFLICT", "");
            }
            Instant expires = Instant.parse(json.path("expiresAt").asText());
            if (!clock.instant().isBefore(expires)) throw new VclException("CURSOR_EXPIRED", "");
            return new Position(
                UUID.fromString(json.path("recordId").asText()),
                json.path("initialDataVersion").asLong(),
                json.path("drifted").asBoolean(),
                expires
            );
        } catch (java.time.DateTimeException invalid) {
            throw invalid();
        } catch (IllegalArgumentException invalid) {
            if (invalid instanceof VclException typed) throw typed;
            throw invalid();
        }
    }

    private byte[] sign(byte[] bytes) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static VclException invalid() {
        return new VclException("CURSOR_INVALID", "");
    }
}
