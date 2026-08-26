package com.voltcanary.ingestion.adapter.opcua;

import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;

public record OpcUaAdapterConfig(
        URI endpoint,
        String endpointReference,
        String securityPolicy,
        OpcUaAuthenticationMode authenticationMode,
        String usernameCredentialRef,
        String passwordCredentialRef,
        String clientCertificateRef,
        String privateKeyRef,
        String trustListRef,
        Duration sessionTimeout,
        Duration publishingInterval,
        Duration samplingInterval,
        int queueSize,
        OpcUaTimestampPolicy timestampPolicy,
        java.util.List<OpcUaNodeMapping> nodes) {

    static OpcUaAdapterConfig from(SourceRuntimeConfig sourceRuntimeConfig) {
        if (!"v1".equalsIgnoreCase(sourceRuntimeConfig.adapterConfigSchemaVersion())) {
            throw new IllegalArgumentException("Unsupported OPC UA adapter config schema version");
        }
        Map<String, Object> config = sourceRuntimeConfig.adapterConfig();
        String endpointUrl = string(config, "endpointUrl", null);
        if (endpointUrl == null) {
            endpointUrl = string(config, "endpoint", null);
        }
        String endpointReference = string(config, "endpointReference", null);
        if ((endpointUrl == null || endpointUrl.isBlank())
                && (endpointReference == null || endpointReference.isBlank())) {
            throw new IllegalArgumentException("OPC UA endpointUrl or endpointReference is required");
        }
        URI endpoint = endpointUrl == null || endpointUrl.isBlank() ? null : URI.create(endpointUrl.trim());
        if (endpoint != null && !"opc.tcp".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("OPC UA endpointUrl must use opc.tcp");
        }
        OpcUaAuthenticationMode authMode = enumValue(
                OpcUaAuthenticationMode.class,
                string(config, "authenticationMode", "ANONYMOUS"),
                "authenticationMode");
        validateAuth(sourceRuntimeConfig.credentialReferences(), authMode);
        Duration sessionTimeout = duration(config, "sessionTimeout", Duration.ofSeconds(30));
        Duration publishingInterval = duration(config, "publishingInterval", Duration.ofSeconds(1));
        Duration samplingInterval = duration(config, "samplingInterval", publishingInterval);
        if (sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("OPC UA sessionTimeout must be positive");
        }
        if (publishingInterval.isZero() || publishingInterval.isNegative()) {
            throw new IllegalArgumentException("OPC UA publishingInterval must be positive");
        }
        if (samplingInterval.isZero() || samplingInterval.isNegative()) {
            throw new IllegalArgumentException("OPC UA samplingInterval must be positive");
        }
        int queueSize = integer(config, "queueSize", 10);
        if (queueSize < 1 || queueSize > 10000) {
            throw new IllegalArgumentException("OPC UA queueSize must be between 1 and 10000");
        }
        return new OpcUaAdapterConfig(
                endpoint,
                clean(endpointReference),
                clean(string(config, "securityPolicy", "None")),
                authMode,
                clean(sourceRuntimeConfig.credentialReferences().get("username")),
                clean(sourceRuntimeConfig.credentialReferences().get("password")),
                clean(sourceRuntimeConfig.credentialReferences().get("clientCertificate")),
                clean(sourceRuntimeConfig.credentialReferences().get("privateKey")),
                clean(sourceRuntimeConfig.credentialReferences().get("trustList")),
                sessionTimeout,
                publishingInterval,
                samplingInterval,
                queueSize,
                enumValue(OpcUaTimestampPolicy.class, string(config, "timestampPolicy", "SOURCE_TIME"), "timestampPolicy"),
                nodes(sourceRuntimeConfig.mapping()));
    }

    private static void validateAuth(Map<String, String> credentialReferences, OpcUaAuthenticationMode authMode) {
        if (authMode == OpcUaAuthenticationMode.USERNAME
                && (!credentialReferences.containsKey("username") || !credentialReferences.containsKey("password"))) {
            throw new IllegalArgumentException("OPC UA username authentication requires username and password references");
        }
        if (authMode == OpcUaAuthenticationMode.CERTIFICATE
                && (!credentialReferences.containsKey("clientCertificate")
                || !credentialReferences.containsKey("privateKey"))) {
            throw new IllegalArgumentException("OPC UA certificate authentication requires clientCertificate and privateKey references");
        }
    }

    private static java.util.List<OpcUaNodeMapping> nodes(Map<String, Object> mapping) {
        Object value = mapping.get("nodes");
        if (!(value instanceof java.util.List<?> rawNodes) || rawNodes.isEmpty()) {
            throw new IllegalArgumentException("OPC UA mapping nodes must be a non-empty array");
        }
        java.util.List<OpcUaNodeMapping> nodes = new ArrayList<>();
        for (Object item : rawNodes) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("OPC UA node mapping entries must be objects");
            }
            nodes.add(node(raw));
        }
        return java.util.List.copyOf(nodes);
    }

    private static OpcUaNodeMapping node(Map<?, ?> raw) {
        String metric = requiredString(raw, "metric");
        String nodeId = string(raw, "nodeId", null);
        String browsePath = string(raw, "browsePath", null);
        if ((nodeId == null || nodeId.isBlank()) && (browsePath == null || browsePath.isBlank())) {
            throw new IllegalArgumentException("OPC UA nodeId or browsePath is required");
        }
        return new OpcUaNodeMapping(
                metric,
                clean(nodeId),
                clean(string(raw, "namespaceUri", null)),
                clean(browsePath),
                clean(string(raw, "dataType", null)),
                decimal(raw, "scale", 1.0),
                decimal(raw, "offset", 0.0),
                clean(string(raw, "unit", null)));
    }

    private static String requiredString(Map<?, ?> config, String key) {
        String value = string(config, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OPC UA " + key + " is required");
        }
        return value.trim();
    }

    private static String string(Map<?, ?> config, String key, String fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("OPC UA " + key + " must be a string");
        }
        return text;
    }

    private static int integer(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("OPC UA " + key + " must be a number");
    }

    private static double decimal(Map<?, ?> config, String key, double fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("OPC UA " + key + " must be a number");
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
        throw new IllegalArgumentException("OPC UA " + key + " must be a duration");
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OPC UA " + field + " must not be blank");
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return Enum.valueOf(type, normalized);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
