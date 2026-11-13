package com.voltcanary.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.common.security.BearerUserIdentity;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.common.test.SignedUserTokens;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceBindingDescription;
import com.voltcanary.vcl.VclSourceBindingProfile;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled authenticated dependency; actual source/native SQL owners are separately tested in device-service. */
final class GrantOwnerFixture {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final VclParser PARSER = new VclParser();
    private static final BearerUserIdentity IDENTITY = new BearerUserIdentity(new JwtAccessTokenService(
            SignedUserTokens.KEY_ID, SignedUserTokens.PUBLIC_KEYS, "voltcanary-identity", "voltcanary-api"));
    record Owner(UUID account, String status) { }
    static final Map<UUID, Owner> OWNERS = new ConcurrentHashMap<>();
    static final Map<UUID, Binding> BINDINGS = new ConcurrentHashMap<>();
    static final Map<UUID, Dataset> DATASETS = new ConcurrentHashMap<>();
    static final AtomicInteger READS = new AtomicInteger();
    static final HttpServer SERVER;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                READS.incrementAndGet();
                int status = 200;
                Object result;
                String path = exchange.getRequestURI().getPath();
                try {
                    if (path.startsWith("/internal/accounts/users/")) {
                        if (!"dev-internal-token".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))) throw new Denied(401);
                        UUID actor = UUID.fromString(path.split("/")[4]);
                        Owner owner = OWNERS.get(actor);
                        if (owner == null) throw new Denied(404);
                        result = Map.of("userId", actor, "accountId", owner.account(), "status", owner.status());
                    } else {
                        UUID actor = UUID.fromString(IDENTITY.verify(exchange.getRequestHeaders().getFirst("Authorization")).subject());
                        Owner owner = OWNERS.get(actor);
                        if (owner == null || !owner.status().equals("ACTIVE")) throw new Denied(403);
                        if (!"schemaRevision=1".equals(exchange.getRequestURI().getQuery())) throw new Denied(404);
                        UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
                        if (path.startsWith("/data/source-bindings/")) {
                            Binding binding = BINDINGS.get(id);
                            if (binding == null || !binding.actor.equals(actor)) throw new Denied(404);
                            result = binding.description();
                        } else {
                            Dataset dataset = DATASETS.get(id);
                            if (!path.startsWith("/data/datasets/") || dataset == null || !dataset.actor.equals(actor)) throw new Denied(404);
                            result = dataset.description();
                        }
                    }
                } catch (Denied failure) { status = failure.status; result = Map.of("code", "UNAVAILABLE"); }
                catch (IllegalArgumentException failure) { status = 401; result = Map.of("code", "UNAUTHORIZED"); }
                byte[] bytes = MAPPER.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            SERVER.start();
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    static UUID actor() {
        UUID actor = UUID.randomUUID();
        OWNERS.put(actor, new Owner(UUID.randomUUID(), "ACTIVE"));
        return actor;
    }

    static String scope(UUID actor) { return VclCanonical.sha256("owner-scope:" + actor + ":" + OWNERS.get(actor).account()); }

    static final class Binding {
        final UUID id = UUID.randomUUID();
        final UUID actor;
        final UUID device = UUID.randomUUID();
        final UUID snapshot = UUID.randomUUID();
        final UUID boolField = UUID.randomUUID();
        final UUID textField = UUID.randomUUID();
        final UUID unsupportedField = UUID.randomUUID();
        volatile boolean active = true;
        volatile long currentRevision = 1;
        volatile String state = VclCanonical.sha256("initial-state");
        Binding(UUID actor) { this.actor = actor; BINDINGS.put(id, this); }

        VclSourceBindingDescription description() {
            var definition = MAPPER.createObjectNode().put("id", id.toString()).put("schemaRevision", 1)
                    .put("profile", VclSourceBindingProfile.VERSION).put("configurationDigest", VclCanonical.sha256("configuration"));
            var fields = definition.putArray("fields");
            fields.addObject().put("id", boolField.toString()).put("label", "Door").put("type", "BOOLEAN").put("nullable", true);
            fields.addObject().put("id", textField.toString()).put("label", "Subject").put("type", "TEXT").put("maxLength", 512).put("nullable", true);
            return new VclSourceBindingDescription(id, OWNERS.get(actor).account(), actor, device, 1, currentRevision, snapshot,
                    definition, VclCanonical.sha256(VclCanonical.encode(definition)), scope(actor), state, active, false, VclSourceBindingProfile.capabilities());
        }
    }

    static final class Dataset {
        final UUID id = UUID.randomUUID();
        final UUID actor;
        final UUID field = UUID.randomUUID();
        final UUID systemField = UUID.randomUUID();
        volatile UUID generation = UUID.randomUUID();
        volatile long dataVersion = 1;
        final JsonNode definition;
        final String digest;
        Dataset(UUID actor) {
            this.actor = actor;
            var document = MAPPER.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
            var spec = document.putObject("spec").put("id", id.toString()).put("revision", 1).put("label", "Private native data");
            spec.putArray("fields").addObject().put("id", field.toString()).put("label", "Name").put("type", "TEXT").put("maxLength", 120).put("nullable", true);
            spec.putArray("keyFieldIds");
            spec.putObject("storage").put("mode", "NATIVE").put("history", "NONE").putArray("indexedFieldIds");
            var parsed = PARSER.parse(document.toString());
            definition = parsed.spec();
            digest = VclCanonical.documentDigest(parsed);
            DATASETS.put(id, this);
        }

        VclDatasetDescription description() {
            var metadata = (ObjectNode) definition.deepCopy();
            metadata.put("generation", generation.toString()).put("dataVersion", dataVersion);
            metadata.putObject("systemFields").put("recordIdFieldId", systemField.toString());
            var capabilities = MAPPER.createObjectNode().put("profile", "native-live-sql-1");
            return new VclDatasetDescription(id, 1, metadata, digest, scope(actor), generation, dataVersion,
                    Set.of(), "native-dense-1", true, false, capabilities);
        }
    }

    private static final class Denied extends RuntimeException {
        final int status;
        Denied(int status) { this.status = status; }
    }
}
