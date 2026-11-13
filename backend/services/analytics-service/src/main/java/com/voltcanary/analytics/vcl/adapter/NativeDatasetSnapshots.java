package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads the ordinary signed owner API. Neither an account ID nor request metadata grants access. */
@Component
public class NativeDatasetSnapshots {
    public record Target(UUID id, long revision) {
        public Target {
            Objects.requireNonNull(id);
            if (revision < 1) throw new IllegalArgumentException("Invalid schema revision");
        }
    }

    private final RestClient client;
    private final VclParser parser = new VclParser();
    private final ObjectMapper mapper = new ObjectMapper();

    public NativeDatasetSnapshots(RestClient.Builder builder, AnalyticsDownstreamProperties properties) {
        var settings = properties.getDeviceService();
        Duration timeout = settings.getTimeout();
        var transport = new OwnerRequestFactory();
        transport.setConnectTimeout(timeout);
        transport.setReadTimeout(timeout);
        client = builder.clone().baseUrl(settings.getBaseUrl()).requestFactory(transport).build();
    }

    /** One short-lived compilation snapshot; never cache it across actors, turns or executions. */
    public NativeDatasetSnapshot capture(String authorization, List<Target> targets) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
            throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        }
        var requested = List.copyOf(targets);
        if (requested.isEmpty() || requested.size() > 64 || requested.stream().distinct().count() != requested.size()
                || requested.stream().map(Target::id).distinct().count() != requested.size()) {
            throw new VclException("OWNER_TARGET_LIMIT", "");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var descriptions = new LinkedHashMap<Target, VclDatasetDescription>();
        requested.forEach(target -> descriptions.put(target, describe(authorization, target, deadline)));
        return new NativeDatasetSnapshot(descriptions, target -> describe(authorization, target, deadline));
    }

    private VclDatasetDescription describe(String authorization, Target target, long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new VclException("OWNER_SNAPSHOT_EXPIRED", "");
        try {
            return client.get().uri("/data/datasets/{id}?schemaRevision={revision}", target.id(), target.revision())
                    .header("Authorization", authorization).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new VclException("OWNER_ACCESS_REVOKED", "");
                        if (status == 404) throw new VclException("DATASET_UNAVAILABLE", "");
                        if (status != 200) throw new VclException("DATASET_OWNER_UNAVAILABLE", "");
                        byte[] bytes = readBounded(response.getBody(), deadline);
                        var json = parser.readJson(bytes);
                        var fields = new java.util.HashSet<String>();
                        json.fieldNames().forEachRemaining(fields::add);
                        if (!fields.equals(Set.of("id", "schemaRevision", "definition", "schemaDigest", "accessScopeDigest",
                                "generation", "dataVersion", "readyIndexedFields", "storageProfile", "liveQueryReady",
                                "historyReady", "queryCapabilities")) || !json.path("liveQueryReady").isBoolean()
                                || !json.path("historyReady").isBoolean()
                                || !json.path("schemaRevision").isIntegralNumber() || !json.path("schemaRevision").canConvertToLong()
                                || !json.path("dataVersion").isIntegralNumber() || !json.path("dataVersion").canConvertToLong()) {
                            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                        }
                        var value = mapper.treeToValue(json, VclDatasetDescription.class);
                        if (!target.id().equals(value.id()) || target.revision() != value.schemaRevision()
                                || !value.id().toString().equals(value.definition().path("id").asText())
                                || value.definition().path("revision").asLong() != value.schemaRevision()
                                || !value.generation().toString().equals(value.definition().path("generation").asText())
                                || value.definition().path("dataVersion").asLong() != value.dataVersion()
                                || !"native-dense-1".equals(value.storageProfile())
                                || !"NATIVE".equals(value.definition().at("/storage/mode").asText())) {
                            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                        }
                        var schema = mapper.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
                        var definition = (com.fasterxml.jackson.databind.node.ObjectNode) value.definition();
                        definition.remove(List.of("generation", "dataVersion", "systemFields"));
                        schema.set("spec", definition);
                        if (!value.schemaDigest().equals(VclCanonical.documentDigest(parser.parse(schema.toString())))) {
                            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                        }
                        return value;
                    });
        } catch (VclException failure) {
            if (java.util.Set.of("OWNER_ACCESS_REVOKED", "DATASET_UNAVAILABLE", "DATASET_OWNER_UNAVAILABLE",
                    "OWNER_SNAPSHOT_INVALID", "OWNER_SNAPSHOT_EXPIRED").contains(failure.code())) throw failure;
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        } catch (RuntimeException failure) {
            // Do not expose upstream response bodies, URLs, labels, credentials or codec diagnostics.
            throw new VclException("DATASET_OWNER_UNAVAILABLE", "");
        }
    }

    static byte[] readBounded(InputStream input, long deadline) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() <= VclParser.MAX_BYTES) {
            if (System.nanoTime() - deadline >= 0) throw new VclException("OWNER_SNAPSHOT_EXPIRED", "");
            int count = input.read(buffer, 0, Math.min(buffer.length, VclParser.MAX_BYTES + 1 - output.size()));
            if (count == -1) break;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class OwnerRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
