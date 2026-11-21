package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.device.config.NativeDependencyProperties;
import com.voltcanary.device.configurable.port.NativeMigrationDependencies;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Separate owner cuts, with fresh caller scope around I/O. No distributed-transaction claim. */
public final class HttpNativeMigrationDependencies implements NativeMigrationDependencies {
    private static final VclParser PARSER = new VclParser();
    private static final int MAX_BYTES = 262_144;
    private final DataSource source;
    private final String token;
    private final Map<String, RestClient> owners;

    public HttpNativeMigrationDependencies(DataSource source, NativeDependencyProperties properties, String token) {
        this.source = source;
        if (token == null || token.isBlank() || properties.timeout() == null || properties.timeout().isNegative()
                || properties.timeout().isZero() || properties.timeout().compareTo(java.time.Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Native dependency owner configuration is invalid");
        }
        this.token = token;
        var clients = new LinkedHashMap<String, RestClient>();
        clients.put("analytics", client(properties.analytics(), properties));
        clients.put("alerts", client(properties.alerts(), properties));
        clients.put("dashboards", client(properties.dashboards(), properties));
        owners = java.util.Collections.unmodifiableMap(clients);
    }

    private static RestClient client(URI base, NativeDependencyProperties properties) {
        if (base == null || base.getHost() == null || base.getUserInfo() != null
                || !java.util.List.of("http", "https").contains(base.getScheme())) {
            throw new IllegalArgumentException("Native dependency owner URI is invalid");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        return JacksonRestClients.builder().baseUrl(base.toString()).requestFactory(factory).build();
    }

    @Override
    public JsonNode inspect(UUID account, UUID actor, UUID dataset, long revision) {
        var scope = NativeRowUnitOfWork.run(source, account, actor, (connection, authority) -> {
            NativeRowTransactions.head(connection, account, dataset, revision);
            return authority;
        });
        var result = JsonNodeFactory.instance.arrayNode();
        for (var owner : owners.entrySet()) {
            JsonNode value;
            try {
                value = owner.getValue().get().uri("/internal/native-datasets/{id}/dependencies?accountId={account}&schemaRevision={revision}", dataset, account, revision)
                        .header("X-Internal-Service-Token", token).exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) throw new VclException("DEPENDENCY_OWNER_UNAVAILABLE", "");
                            byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
                            if (bytes.length > MAX_BYTES) throw new VclException("DEPENDENCY_OWNER_UNAVAILABLE", "");
                            return PARSER.readJson(bytes);
                        });
            } catch (RuntimeException unavailable) {
                throw new VclException("DEPENDENCY_OWNER_UNAVAILABLE", "");
            }
            if (value == null || !value.path("owner").asText().equals(owner.getKey())) {
                throw new VclException("DEPENDENCY_OWNER_UNAVAILABLE", "");
            }
            result.add(value);
        }
        NativeRowUnitOfWork.run(source, account, actor, (connection, authority) -> {
            if (!scope.equals(authority)) throw new VclException("ACCESS_SCOPE_CHANGED", "");
            var current = NativeRowTransactions.head(connection, account, dataset, revision);
            NativeMigrationImpact.verifyOwners(result, current);
            return null;
        });
        return result;
    }
}
