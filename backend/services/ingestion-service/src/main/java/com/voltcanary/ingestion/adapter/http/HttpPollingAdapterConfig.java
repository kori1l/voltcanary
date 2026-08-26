package com.voltcanary.ingestion.adapter.http;

import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;

record HttpPollingAdapterConfig(
        String method,
        URI endpoint,
        Duration interval,
        Duration timeout,
        Map<String, String> headers,
        Map<String, String> credentialHeaders) {

    static HttpPollingAdapterConfig from(SourceRuntimeConfig sourceRuntimeConfig) {
        if (!"v1".equalsIgnoreCase(sourceRuntimeConfig.adapterConfigSchemaVersion())) {
            throw new IllegalArgumentException("Unsupported HTTP polling adapter config schema version");
        }
        Map<String, Object> config = sourceRuntimeConfig.adapterConfig();
        String method = string(config, "method", "GET").toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new IllegalArgumentException("HTTP polling method must be GET or POST");
        }
        String endpoint = string(config, "url", null);
        if (endpoint == null) {
            endpoint = string(config, "endpoint", null);
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("HTTP polling endpoint is required");
        }
        URI uri = URI.create(endpoint.trim());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("HTTP polling endpoint must use http or https");
        }
        Duration interval = duration(config, "pollingInterval", null);
        if (interval == null) {
            interval = duration(config, "pollInterval", null);
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("HTTP polling interval must be positive");
        }
        Duration timeout = duration(config, "timeout", Duration.ofSeconds(5));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("HTTP polling timeout must be positive");
        }
        return new HttpPollingAdapterConfig(
                method,
                uri,
                interval,
                timeout,
                stringMap(config.get("headers"), "headers"),
                stringMap(config.get("credentialHeaders"), "credentialHeaders"));
    }

    private static String string(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("HTTP polling " + key + " must be a string");
        }
        return text;
    }

    private static Duration duration(Map<String, Object> config, String key, Duration fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return Duration.ofMillis(number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return DurationStyle.detectAndParse(text.trim());
        }
        throw new IllegalArgumentException("HTTP polling " + key + " must be a duration");
    }

    private static Map<String, String> stringMap(Object value, String key) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("HTTP polling " + key + " must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("HTTP polling " + key + " names must be strings");
            }
            if (!(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException("HTTP polling " + key + " values must be strings");
            }
            result.put(name, text);
        }
        return Map.copyOf(result);
    }
}
