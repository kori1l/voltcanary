package com.voltcanary.common.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Participants read the authoritative deletion receipt, never a caller-supplied policy. */
public final class DeviceDeletionAuthorization {
    private final String token;
    private final RestClient devices;

    public DeviceDeletionAuthorization(String deviceUrl, String token) {
        this.token = token;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        devices = RestClient.builder().baseUrl(deviceUrl).requestFactory(factory).build();
    }

    public record Deletion(boolean purge, java.util.List<UUID> sourceIds) { }

    public Deletion requireDeletion(UUID deviceId, String suppliedToken) {
        if (token == null || token.isBlank() || suppliedToken == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal authorization required");
        }
        Map<?, ?> receipt = devices.get().uri("/internal/device-deletions/{id}", deviceId)
                .header("X-Internal-Service-Token", token).retrieve().body(Map.class);
        if (receipt == null || !deviceId.toString().equals(String.valueOf(receipt.get("deviceId")))
                || !(receipt.get("deleteHistory") instanceof Boolean) || !(receipt.get("sourceIds") instanceof java.util.List<?>)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deletion receipt required");
        }
        var sourceIds = receipt.get("sourceIds") instanceof java.util.List<?> ids
                ? ids.stream().map(id -> UUID.fromString(String.valueOf(id))).toList() : java.util.List.<UUID>of();
        return new Deletion(Boolean.TRUE.equals(receipt.get("deleteHistory")), sourceIds);
    }
}
