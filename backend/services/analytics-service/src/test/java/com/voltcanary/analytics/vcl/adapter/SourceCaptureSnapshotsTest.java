package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationProfile;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceBindingDescription;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceCaptureSnapshotsTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final UUID ID = UUID.randomUUID(),
        ACCOUNT = UUID.randomUUID(),
        ACTOR = UUID.randomUUID(),
        DEVICE = UUID.randomUUID(),
        SCHEMA = UUID.randomUUID(),
        GENERATION = UUID.randomUUID();
    private static final String AUTH = "Bearer private-capture-credential";
    private SourceCaptureSnapshots snapshots;
    private HttpServer server;
    private ObjectNode original;
    private byte[] body;
    private int status, requests;
    private String authorization, path;
    private com.voltcanary.vcl.VclObservationRead pageRequest;
    private java.util.function.Consumer<ObjectNode> pageMutation;
    private Runnable afterPage;
    private int pageStatus = 200;

    @BeforeEach
    void start() throws Exception {
        var definition = JSON.createObjectNode()
            .put("id", ID.toString())
            .put("schemaRevision", 1)
            .put("profile", VclSourceBindingProfile.VERSION)
            .put("configurationDigest", "a".repeat(64));
        definition
            .putArray("fields")
            .addObject()
            .put("id", UUID.randomUUID().toString())
            .put("type", "BOOLEAN")
            .put("label", "Switch")
            .put("nullable", true);
        String digest = VclCanonical.sha256(VclCanonical.encode(definition));
        var source = new VclSourceBindingDescription(
            ID,
            ACCOUNT,
            ACTOR,
            DEVICE,
            1,
            1,
            SCHEMA,
            definition,
            digest,
            "a".repeat(64),
            "a".repeat(64),
            true,
            false,
            VclSourceBindingProfile.capabilities()
        );
        var pin = new VclObservationReadiness.Pin(ID, ACCOUNT, DEVICE, "source", 1, SCHEMA, digest);
        var capture = new VclObservationReadiness(
            pin,
            VclObservationProfile.VERSION,
            "READY",
            GENERATION,
            "0",
            "5",
            Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),
            true,
            false,
            false,
            "UNKNOWN",
            "UNKNOWN",
            "UNQUALIFIED",
            null
        );
        original = JSON.valueToTree(new VclSourceCaptureDescription(source, capture));
        body(original);
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++;
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            path = exchange.getRequestURI().toString();
            byte[] response = body;
            if (path.endsWith("/observations")) {
                pageRequest = com.voltcanary.vcl.VclObservationRead.parse(
                    exchange.getRequestBody().readAllBytes()
                );
                var row = JSON.createObjectNode()
                    .put("profile", VclObservationProfile.VERSION)
                    .put("inputEventId", UUID.randomUUID().toString())
                    .put("inputGeneration", pageRequest.inputGeneration().toString())
                    .put("checkpoint", "1")
                    .put("eventTime", "2026-11-05T00:00:00Z")
                    .put("receivedAt", "2026-11-05T00:00:01Z")
                    .put("recordedAt", "2026-11-05T00:00:02Z")
                    .put("upstreamCoverage", "UNKNOWN")
                    .put("eventTimeProvenance", "UNQUALIFIED")
                    .put("negativeEvidenceReady", false)
                    .put("ruleExecutionReady", false);
                row.set("bindingSchemaPin", JSON.valueToTree(pageRequest.pin()));
                row.putArray("fields")
                    .addObject()
                    .put("fieldId", original.at("/source/definition/fields/0/id").asText())
                    .put("type", "BOOLEAN")
                    .put("knowledge", "KNOWN")
                    .putNull("reason")
                    .put("value", false);
                var value = (ObjectNode) JSON.valueToTree(
                    com.voltcanary.vcl.VclObservationPage.of(pageRequest, List.of(row), "PAGE_ROWS")
                );
                if (pageMutation != null) pageMutation.accept(value);
                if (afterPage != null) afterPage.run();
                response = value.toString().getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Location", "/redirected");
            if (path.endsWith("/observations") && pageStatus != 200) response =
                "private observation owner detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(
                path.endsWith("/observations") ? pageStatus : status,
                response.length
            );
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        var properties = new AnalyticsDownstreamProperties();
        properties
            .getDeviceService()
            .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getDeviceService().setTimeout(Duration.ofSeconds(1));
        snapshots = new SourceCaptureSnapshots(JacksonRestClients.builder(), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void actualGenerationAndCapturedHeadAreMetadataRatherThanTemporalAuthority() {
        var value = capture();
        assertEquals(GENERATION, value.inputGeneration());
        assertNotEquals(SCHEMA, value.inputGeneration());
        assertEquals("5", value.capturedHeadCheckpoint());
        assertEquals(AUTH, authorization);
        assertEquals("/data/source-bindings/" + ID + "/capture?schemaRevision=1", path);
        assertTrue(value.binding(ID, 1).isPresent());
        assertTrue(value.binding(ID, 2).isEmpty());
        assertTrue(value.dataset(ID, 1).isEmpty());
        for (var resource : VclMetadata.Resource.values()) assertFalse(value.permits(resource, ID));
        code("SOURCE_RUNTIME_UNAVAILABLE", value::requireRuntimeReady);
        code("NEGATIVE_EVIDENCE_UNAVAILABLE", value::requireNegativeEvidence);
        assertFalse(value.toString().contains("private-capture-credential"));
    }

    @Test
    void headMayAdvanceButGenerationCutoverAndScopeCannotDriftOrRegress() {
        var value = capture();
        var changed = original.deepCopy();
        ((ObjectNode) changed.path("capture")).put("headCheckpoint", "6");
        body(changed);
        assertEquals("5", value.capturedHeadCheckpoint());
        changed = original.deepCopy();
        ((ObjectNode) changed.path("capture")).put("headCheckpoint", "4");
        body(changed);
        code("CAPTURE_CHECKPOINT_REGRESSED", value::inputGeneration);
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
            json -> json.put("inputGeneration", UUID.randomUUID().toString()),
            json -> json.put("generationStartCheckpoint", "1"),
            json ->
                json.put(
                    "generationStartedAt",
                    Instant.now().truncatedTo(ChronoUnit.MICROS).toString()
                )
        )) {
            changed = original.deepCopy();
            change.accept((ObjectNode) changed.path("capture"));
            body(changed);
            code("CAPTURE_GENERATION_CHANGED", value::inputGeneration);
        }
        changed = original.deepCopy();
        ((ObjectNode) changed.path("source")).put("accessScopeDigest", "b".repeat(64));
        body(changed);
        code("ACCESS_SCOPE_CHANGED", value::inputGeneration);
        changed = original.deepCopy();
        ((ObjectNode) changed.path("source")).put("sourceStateDigest", "b".repeat(64));
        body(changed);
        code("METADATA_SNAPSHOT_CHANGED", value::inputGeneration);
        changed = original.deepCopy();
        ((ObjectNode) changed.at("/capture/pin")).put("externalSourceId", "retargeted-source");
        body(changed);
        code("METADATA_SNAPSHOT_CHANGED", value::inputGeneration);
    }

    @Test
    void fabricatedReadinessGenerationAndNonExactCountersFailClosed() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
            json -> json.put("ruleExecutionReady", true),
            json -> json.put("negativeEvidenceReady", true),
            json -> json.put("transitionCoverage", "COMPLETE"),
            json -> json.put("eventTimeProvenance", "QUALIFIED"),
            json -> json.put("maximumGapUs", 1000),
            json -> json.put("inputGeneration", SCHEMA.toString()),
            json -> json.put("inputGeneration", ID.toString()),
            json -> json.put("generationStartedAt", "invalid"),
            json -> json.put("generationStartCheckpoint", "6"),
            json -> json.put("headCheckpoint", 5),
            json -> json.put("headCheckpoint", "05"),
            json -> json.put("headCheckpoint", "9223372036854775808"),
            json -> json.put("profile", VclObservationProfile.LEGACY_VERSION),
            json -> json.put("approved", true),
            json -> ((ObjectNode) json.path("pin")).put("accountId", UUID.randomUUID().toString())
        )) {
            var changed = original.deepCopy();
            change.accept((ObjectNode) changed.path("capture"));
            body(changed);
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
        for (String field : List.of("actorId", "accountId")) {
            var changed = original.deepCopy();
            ((ObjectNode) changed.path("source")).put(field, UUID.randomUUID().toString());
            body(changed);
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
        for (byte[] invalid : List.of(
            (original + "{}").getBytes(StandardCharsets.UTF_8),
            " ".repeat(1_048_577).getBytes(StandardCharsets.UTF_8),
            new byte[] { (byte) 0xC3, 0x28 },
            original
                .toString()
                .replace("\"journalReady\":true", "\"journalReady\":true,\"journalReady\":true")
                .getBytes(StandardCharsets.UTF_8)
        )) {
            body = invalid;
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
    }

    @Test
    void pendingJournalAndSourceDisableInvalidateEveryCapturedUse() {
        var value = capture();
        var changed = original.deepCopy();
        ((ObjectNode) changed.path("capture"))
            .put("state", "INPUT_PENDING")
            .put("journalReady", false);
        body(changed);
        code("SOURCE_CAPTURE_UNAVAILABLE", value::inputGeneration);
        changed = original.deepCopy();
        var source = VclSourceCaptureDescription.parse(JSONBytes(original)).source();
        ((ObjectNode) changed.path("source")).put("sourceActive", false);
        changed.set(
            "capture",
            JSON.valueToTree(
                VclObservationReadiness.absent(
                    new VclObservationReadiness.Pin(
                        ID,
                        ACCOUNT,
                        DEVICE,
                        "source",
                        1,
                        SCHEMA,
                        source.definitionDigest()
                    ),
                    "DISABLED"
                )
            )
        );
        body(changed);
        code("SOURCE_NOT_ACTIVE", value::inputGeneration);
    }

    @Test
    void failuresRedirectsAndMissingIdentityNeverReuseCachedCaptureAuthority() {
        var value = capture();
        for (int response : List.of(302, 401, 403, 404, 409, 500, 503)) {
            status = response;
            body = "private upstream detail".getBytes(StandardCharsets.UTF_8);
            int before = requests;
            var failure = assertThrows(VclException.class, value::inputGeneration);
            assertEquals(
                response == 401 || response == 403
                    ? "OWNER_ACCESS_REVOKED"
                    : response == 404
                      ? "SOURCE_CAPTURE_UNAVAILABLE"
                      : response == 409
                        ? "METADATA_SNAPSHOT_CHANGED"
                        : "SOURCE_CAPTURE_OWNER_UNAVAILABLE",
                failure.code()
            );
            assertEquals(before + 1, requests);
            assertFalse(failure.getMessage().contains("private"));
        }
        int before = requests;
        code("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.capture(null, ACTOR, ACCOUNT, ID, 1));
        code("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.capture(AUTH, null, ACCOUNT, ID, 1));
        code("OWNER_TARGET_INVALID", () -> snapshots.capture(AUTH, ACTOR, ACCOUNT, ID, 0));
        assertEquals(before, requests);
    }

    @Test
    void signedPageReadPinsOriginalGenerationAndHeadDespiteLaterProgress() {
        var value = capture();
        var changed = original.deepCopy();
        ((ObjectNode) changed.path("capture")).put("headCheckpoint", "6");
        body(changed);
        var page = value.readPage("0", 1);
        assertEquals(GENERATION, pageRequest.inputGeneration());
        assertEquals("5", pageRequest.throughCheckpoint());
        assertEquals("0", pageRequest.afterCheckpoint());
        assertEquals(1, pageRequest.limit());
        assertFalse(page.observations().getFirst().at("/fields/0/value").booleanValue());
        assertFalse(page.ruleExecutionReady());
        assertEquals(AUTH, authorization);
        assertEquals("5", value.capturedHeadCheckpoint());
    }

    @Test
    void pageGenerationFlagsOrFieldsCannotReplaceTheAuthenticatedReadSet() {
        var value = capture();
        for (java.util.function.Consumer<ObjectNode> change : List.<
            java.util.function.Consumer<ObjectNode>
        >of(
            json -> json.put("ruleExecutionReady", true),
            json ->
                ((ObjectNode) json.at("/request/pin")).put(
                    "accountId",
                    UUID.randomUUID().toString()
                ),
            json ->
                ((ObjectNode) json.at("/observations/0/fields/0")).put(
                    "fieldId",
                    UUID.randomUUID().toString()
                ),
            json ->
                ((ObjectNode) json.at("/observations/0")).put(
                    "inputGeneration",
                    UUID.randomUUID().toString()
                )
        )) {
            pageMutation = change;
            code("OWNER_SNAPSHOT_INVALID", () -> value.readPage("0", 1));
        }
    }

    @Test
    void ownerDriftDuringPageReadInvalidatesAlreadyReceivedFacts() {
        var value = capture();
        afterPage = () -> {
            var changed = original.deepCopy();
            ((ObjectNode) changed.path("source")).put("accessScopeDigest", "b".repeat(64));
            body(changed);
        };
        code("ACCESS_SCOPE_CHANGED", () -> value.readPage("0", 1));
    }

    @Test
    void invalidPageBoundsCannotDispatchAnObservationRead() {
        var value = capture();
        code("OBSERVATION_READ_INVALID", () -> value.readPage("6", 1));
        code("OBSERVATION_READ_INVALID", () -> value.readPage("0", 129));
        code("OBSERVATION_READ_INVALID", () -> value.readPage("00", 1));
        assertEquals(null, pageRequest);
    }

    @Test
    void pageFailuresAndRedirectsCannotReuseCaptureAuthorityOrExposeBodies() {
        var value = capture();
        for (int response : List.of(302, 401, 403, 404, 409, 422, 503)) {
            pageStatus = response;
            int before = requests;
            var failure = assertThrows(VclException.class, () -> value.readPage("0", 1));
            assertEquals(
                response == 401 || response == 403
                    ? "OWNER_ACCESS_REVOKED"
                    : response == 404
                      ? "SOURCE_BINDING_UNAVAILABLE"
                      : response == 409
                        ? "METADATA_SNAPSHOT_CHANGED"
                        : response == 422
                          ? "SOURCE_CAPTURE_UNAVAILABLE"
                          : "SOURCE_CAPTURE_OWNER_UNAVAILABLE",
                failure.code()
            );
            assertEquals(before + 2, requests);
            assertFalse(failure.getMessage().contains("private observation owner detail"));
        }
    }

    private SourceCaptureSnapshot capture() {
        return snapshots.capture(AUTH, ACTOR, ACCOUNT, ID, 1);
    }

    private void body(ObjectNode value) {
        body = JSONBytes(value);
    }

    private static byte[] JSONBytes(ObjectNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void code(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(VclException.class, action).code());
    }
}
