package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDatasetSnapshotsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final VclParser PARSER = new VclParser();
    private static final UUID ID = UUID.randomUUID();
    private static final UUID FIELD = UUID.randomUUID();
    private static final UUID SYSTEM = UUID.randomUUID();
    private static final String AUTH = "Bearer private-test-credential";
    private HttpServer server;
    private NativeDatasetSnapshots snapshots;
    private NativeAsOfOwner asOfOwner;
    private volatile String body;
    private volatile int status;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String receivedAuth;
    private volatile String receivedPath;

    @BeforeEach
    void start() throws Exception {
        body = description().toString();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            receivedAuth = exchange.getRequestHeaders().getFirst("Authorization");
            receivedPath = exchange.getRequestURI().toString();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var properties = new AnalyticsDownstreamProperties();
        properties.getDeviceService().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getDeviceService().setTimeout(Duration.ofSeconds(2));
        snapshots = new NativeDatasetSnapshots(JacksonRestClients.builder(), properties);
        asOfOwner = new NativeAsOfOwner(JacksonRestClients.builder(), properties);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void asOfReadsUseSignedOwnerAndRejectBrokenIntervalCoverage() {
        UUID snapshot = UUID.randomUUID();
        UUID interval = UUID.randomUUID();
        UUID startField = UUID.randomUUID();
        UUID endField = UUID.randomUUID();
        var query = PARSER.parse("""
                {"language":"VCL/1","kind":"QUERY","spec":{
                  "target":{"datasetId":"%s","schemaRevision":1},"select":["%s","%s"],
                  "where":{"op":"CONST","literal":{"type":"BOOLEAN","value":true}},
                  "order":[{"fieldId":"%s","direction":"ASC"}],"limit":10,"cursor":null,
                  "read":{"mode":"SNAPSHOT","snapshotId":"%s"}}}
                """.formatted(ID, FIELD, interval, SYSTEM, snapshot));
        var result = MAPPER.createObjectNode();
        var manifest = result.putObject("readManifest").put("datasetId", ID.toString()).put("schemaRevision", 1)
                .put("snapshotId", snapshot.toString()).put("consistency", "SNAPSHOT");
        var segment = result.putArray("segments").addObject().put("status", "UNKNOWN")
                .put("reason", "ASOF_MISSING").putNull("row").put("start", "2026-11-05T12:00:00Z")
                .put("end", "2026-11-05T12:01:00Z");
        body = result.toString();
        var start = java.time.Instant.parse(segment.path("start").textValue());
        var end = java.time.Instant.parse(segment.path("end").textValue());
        var event = java.util.Map.<UUID, com.fasterxml.jackson.databind.JsonNode>of(
                FIELD, MAPPER.createObjectNode().put("type", "BOOLEAN").put("value", true),
                startField, MAPPER.createObjectNode().put("type", "INSTANT").put("value", start.toString()),
                endField, MAPPER.createObjectNode().put("type", "INSTANT").put("value", end.toString()));
        var config = MAPPER.createObjectNode();
        config.set("target", query.spec().path("target"));
        config.putArray("select").add(FIELD.toString());
        config.putArray("keyPairs").addObject().put("eventFieldId", FIELD.toString()).put("recordFieldId", FIELD.toString());
        config.putObject("temporal").put("mode", "INTERVAL_SPLIT").put("startFieldId", startField.toString())
                .put("endFieldId", endField.toString());
        var node = new com.voltcanary.vcl.VclCompilation.Node("lookup", "lookup_asof", java.util.Map.of(),
                config, "EVENT", java.util.Map.of(), null);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        assertEquals(result, asOfOwner.lookup(AUTH, node, query, interval, event, null, manifest, deadline));
        assertEquals(AUTH, receivedAuth);
        assertEquals("/data/datasets/" + ID + "/query-asof-split", receivedPath);
        segment.put("start", "2026-11-05T12:00:01Z");
        body = result.toString();
        assertEquals("OWNER_SNAPSHOT_INVALID", assertThrows(VclException.class,
                () -> asOfOwner.lookup(AUTH, node, query, interval, event, null, manifest, deadline)).code());
    }

    @Test
    void sharedCompilerUsesSignedOwnerMetadataAndRetainsReadinessWithoutGrantingRuleAuthority() {
        var snapshot = capture();
        var query = PARSER.parse("""
                {"language":"VCL/1","kind":"QUERY","spec":{
                  "target":{"datasetId":"%s","schemaRevision":1},"select":["%s"],
                  "where":{"op":"CONST","literal":{"type":"BOOLEAN","value":true}},
                  "order":[{"fieldId":"%s","direction":"ASC"}],"limit":10,"cursor":null,"read":{"mode":"LIVE"}}}
                """.formatted(ID, FIELD, SYSTEM));
        var compiled = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard()).compile(query, snapshot);
        assertEquals(ID, compiled.dependencies().getFirst().id());
        assertEquals(AUTH, receivedAuth);
        assertEquals("/data/datasets/" + ID + "?schemaRevision=1", receivedPath);
        assertTrue(snapshot.description(ID, 1).orElseThrow().liveQueryReady());
        assertFalse(snapshot.description(ID, 1).orElseThrow().historyReady());
        assertTrue(snapshot.binding(ID, 1).isEmpty());
        for (var resource : VclMetadata.Resource.values()) assertFalse(snapshot.permits(resource, ID));
        assertFalse(snapshot.toString().contains("private"));
        var definition = (ObjectNode) snapshot.dataset(ID, 1).orElseThrow().definition();
        definition.put("label", "tampered");
        assertEquals("private label", snapshot.dataset(ID, 1).orElseThrow().definition().path("label").asText());
    }

    @Test
    void reauthorizationRejectsRevocationAndNeverUsesCachedDataAsAuthority() {
        var snapshot = capture();
        status = 403;
        assertCode("OWNER_ACCESS_REVOKED", snapshot::reauthorize);
        assertCode("OWNER_ACCESS_REVOKED", snapshot::accessScopeDigest);
        assertCode("OWNER_ACCESS_REVOKED", () -> snapshot.dataset(ID, 1));
        assertCode("OWNER_ACCESS_REVOKED", () -> snapshot.description(ID, 1));
    }

    @Test
    void changedScopeGenerationDataIndexAndCapabilityPinsRequireNewPreparation() throws Exception {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                json -> json.put("accessScopeDigest", "b".repeat(64)),
                json -> {
                    String generation = UUID.randomUUID().toString();
                    json.put("generation", generation);
                    ((ObjectNode) json.path("definition")).put("generation", generation);
                },
                json -> {
                    json.put("dataVersion", 2);
                    ((ObjectNode) json.path("definition")).put("dataVersion", 2);
                },
                json -> json.putArray("readyIndexedFields"),
                json -> json.put("historyReady", true),
                json -> ((ObjectNode) json.path("queryCapabilities")).put("profile", "changed"))) {
            body = description().toString();
            var snapshot = capture();
            var changed = description();
            change.accept(changed);
            body = changed.toString();
            String code = changed.path("accessScopeDigest").asText().equals("b".repeat(64))
                    ? "ACCESS_SCOPE_CHANGED" : "METADATA_SNAPSHOT_CHANGED";
            assertCode(code, snapshot::reauthorize);
        }
    }

    @Test
    void invalidOrUntrustedOwnerResponsesNeverBecomeMetadata() throws Exception {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                json -> json.put("id", UUID.randomUUID().toString()),
                json -> json.put("schemaRevision", 2),
                json -> json.put("schemaDigest", "c".repeat(64)),
                json -> json.put("storageProfile", "external"),
                json -> json.put("dataVersion", 2),
                json -> json.put("dataVersion", 1.5),
                json -> json.put("schemaRevision", 1.5),
                json -> json.put("sql", "private SQL"),
                json -> json.remove("historyReady"))) {
            var changed = description();
            change.accept(changed);
            body = changed.toString();
            assertThrows(VclException.class, this::capture);
        }
        body = description().toString().replace("\"liveQueryReady\":true", "\"liveQueryReady\":true,\"liveQueryReady\":true");
        assertCode("OWNER_SNAPSHOT_INVALID", this::capture);
        body = " ".repeat(VclParser.MAX_BYTES + 1);
        assertCode("OWNER_SNAPSHOT_INVALID", this::capture);
    }

    @Test
    void redirectAndOwnerFailureAreNotRetriedAndNeverLeakResponseBodies() {
        for (int responseStatus : List.of(302, 401, 403, 404, 409, 500, 503)) {
            status = responseStatus;
            body = "private upstream SQL and credentials";
            int before = requests.get();
            var error = assertThrows(VclException.class, this::capture);
            assertEquals(before + 1, requests.get());
            assertFalse(error.getMessage().contains("private"));
            assertEquals(responseStatus == 401 || responseStatus == 403 ? "OWNER_ACCESS_REVOKED"
                    : responseStatus == 404 ? "DATASET_UNAVAILABLE" : "DATASET_OWNER_UNAVAILABLE", error.code());
        }
    }

    @Test
    void invalidAuthDuplicateTargetsAndUncapturedResourcesCannotExpandScope() {
        var target = new NativeDatasetSnapshots.Target(ID, 1);
        assertCode("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.capture(null, List.of(target)));
        assertCode("OWNER_TARGET_LIMIT", () -> snapshots.capture(AUTH, List.of()));
        assertCode("OWNER_TARGET_LIMIT", () -> snapshots.capture(AUTH, List.of(target, target)));
        assertCode("OWNER_TARGET_LIMIT", () -> snapshots.capture(AUTH, List.of(target, new NativeDatasetSnapshots.Target(ID, 2))));
        assertEquals(0, requests.get());
        var snapshot = capture();
        assertTrue(snapshot.dataset(UUID.randomUUID(), 1).isEmpty());
        assertTrue(snapshot.dataset(ID, 2).isEmpty());
    }

    private NativeDatasetSnapshot capture() {
        return snapshots.capture(AUTH, List.of(new NativeDatasetSnapshots.Target(ID, 1)));
    }

    @Test
    void transportReaderBoundsBytesAndRejectsAnExpiredAbsoluteDeadline() throws Exception {
        byte[] oversized = new byte[VclParser.MAX_BYTES + 100];
        var bounded = NativeDatasetSnapshots.readBounded(new java.io.ByteArrayInputStream(oversized),
                System.nanoTime() + Duration.ofSeconds(1).toNanos());
        assertEquals(VclParser.MAX_BYTES + 1, bounded.length);
        assertCode("OWNER_SNAPSHOT_EXPIRED", () -> NativeDatasetSnapshots.readBounded(
                new java.io.ByteArrayInputStream(new byte[0]), System.nanoTime() - 1));
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(VclException.class, action).code());
    }

    private ObjectNode description() throws Exception {
        var collection = PARSER.parse("""
                {"language":"VCL/1","kind":"COLLECTION","spec":{
                 "id":"%s","revision":1,"label":"private label",
                 "fields":[{"id":"%s","label":"Name","type":"TEXT","maxLength":120,"nullable":false}],
                 "storage":{"mode":"NATIVE","indexedFieldIds":["%s"],"history":"NONE"},"keyFieldIds":["%s"]}}
                """.formatted(ID, FIELD, FIELD, FIELD));
        // Stable transport head across independently serialized responses.
        UUID generation = UUID.nameUUIDFromBytes(ID.toString().getBytes(StandardCharsets.UTF_8));
        var definition = (ObjectNode) collection.spec();
        definition.put("generation", generation.toString()).put("dataVersion", 1);
        definition.putObject("systemFields").put("recordIdFieldId", SYSTEM.toString());
        var json = MAPPER.createObjectNode().put("id", ID.toString()).put("schemaRevision", 1)
                .put("schemaDigest", VclCanonical.documentDigest(collection)).put("accessScopeDigest", "a".repeat(64))
                .put("generation", generation.toString()).put("dataVersion", 1).put("storageProfile", "native-dense-1")
                .put("liveQueryReady", true).put("historyReady", false);
        json.set("definition", definition);
        json.putArray("readyIndexedFields").add(FIELD.toString());
        json.putObject("queryCapabilities").put("profile", "native-live-sql-1");
        return json;
    }
}
