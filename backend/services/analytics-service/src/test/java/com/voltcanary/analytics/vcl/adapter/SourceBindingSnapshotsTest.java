package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.voltcanary.vcl.VclSourceBindingProfile;
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

class SourceBindingSnapshotsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();
    private static final UUID SNAPSHOT = UUID.randomUUID();
    private static final String AUTHORIZATION = "Bearer private-source-credential";
    private HttpServer server;
    private SourceBindingSnapshots snapshots;
    private volatile String body;
    private volatile int status;
    private volatile String actualAuthorization;
    private volatile String actualPath;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        body = description().toString();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            actualAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            actualPath = exchange.getRequestURI().toString();
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        var properties = new AnalyticsDownstreamProperties();
        properties
            .getDeviceService()
            .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getDeviceService().setTimeout(Duration.ofSeconds(2));
        snapshots = new SourceBindingSnapshots(JacksonRestClients.builder(), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void signedPinnedBindingMetadataNeverConfersRuntimeOrGrantAuthority() {
        var value = capture();
        assertEquals(ID, value.binding(ID, 1).orElseThrow().id());
        assertEquals(AUTHORIZATION, actualAuthorization);
        assertEquals("/data/source-bindings/" + ID + "?schemaRevision=1", actualPath);
        assertTrue(value.binding(ID, 2).isEmpty());
        assertTrue(value.dataset(ID, 1).isEmpty());
        for (var resource : VclMetadata.Resource.values()) assertFalse(value.permits(resource, ID));
        code("SOURCE_CAPTURE_UNAVAILABLE", value::requireRuntimeReady);
        assertFalse(value.toString().contains("private"));
    }

    @Test
    void responseCannotReplaceRequestedActorAccountResourceOrRevision() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
            json -> json.put("actorId", UUID.randomUUID().toString()),
            json -> json.put("accountId", UUID.randomUUID().toString()),
            json -> json.put("id", UUID.randomUUID().toString()),
            json -> json.put("schemaRevision", 2),
            json -> json.put("schemaRevision", 1.5),
            json -> json.put("currentSchemaRevision", 1.5),
            json -> json.put("ruleExecutionReady", true),
            json ->
                ((ObjectNode) json.path("captureCapabilities")).put("negativeEvidenceReady", true),
            json -> json.put("approved", true),
            json -> json.put("definitionDigest", "b".repeat(64))
        )) {
            var value = description();
            change.accept(value);
            body = value.toString();
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
    }

    @Test
    void ownerRevocationSourceDisableAndHeadChangesInvalidateCapturedPins() {
        var value = capture();
        status = 403;
        code("OWNER_ACCESS_REVOKED", value::accessScopeDigest);
        code("OWNER_ACCESS_REVOKED", () -> value.binding(ID, 1));
        status = 200;
        var changed = description().put("sourceActive", false);
        body = changed.toString();
        code("SOURCE_NOT_ACTIVE", value::accessScopeDigest);
        body = description().put("currentSchemaRevision", 2).toString();
        code("BINDING_REVISION_CHANGED", value::accessScopeDigest);
        body = description().put("sourceStateDigest", "b".repeat(64)).toString();
        code("METADATA_SNAPSHOT_CHANGED", value::accessScopeDigest);
        body = description().put("snapshotId", UUID.randomUUID().toString()).toString();
        code("METADATA_SNAPSHOT_CHANGED", value::accessScopeDigest);
        body = description().put("accessScopeDigest", "b".repeat(64)).toString();
        code("ACCESS_SCOPE_CHANGED", value::accessScopeDigest);
    }

    @Test
    void strictOwnerCodecRejectsDuplicatesMissingFieldsOversizedAndUnsafeJson() {
        body = description()
            .toString()
            .replace("\"sourceActive\":true", "\"sourceActive\":true,\"sourceActive\":true");
        code("OWNER_SNAPSHOT_INVALID", this::capture);
        var missing = description();
        missing.remove("captureCapabilities");
        body = missing.toString();
        code("OWNER_SNAPSHOT_INVALID", this::capture);
        body = description().toString() + "{}";
        code("OWNER_SNAPSHOT_INVALID", this::capture);
        body = " ".repeat(1_048_577);
        code("OWNER_SNAPSHOT_INVALID", this::capture);
    }

    @Test
    void failureBodiesAndRedirectsNeverReachMetadataAndAreNotRetried() {
        for (int responseStatus : List.of(302, 401, 403, 404, 409, 500, 503)) {
            status = responseStatus;
            body = "private upstream SQL";
            int before = requests.get();
            var failure = assertThrows(VclException.class, this::capture);
            assertEquals(before + 1, requests.get());
            assertFalse(failure.getMessage().contains("private"));
            assertEquals(
                responseStatus == 401 || responseStatus == 403
                    ? "OWNER_ACCESS_REVOKED"
                    : responseStatus == 404
                      ? "SOURCE_BINDING_UNAVAILABLE"
                      : "SOURCE_BINDING_OWNER_UNAVAILABLE",
                failure.code()
            );
        }
    }

    @Test
    void untrustedTargetsAndMissingIdentityFailBeforeOwnerIo() {
        var target = new SourceBindingSnapshots.Target(ID, 1);
        code("OWNER_AUTHENTICATION_REQUIRED", () ->
            snapshots.capture(null, ACTOR, ACCOUNT, List.of(target))
        );
        code("OWNER_AUTHENTICATION_REQUIRED", () ->
            snapshots.capture(AUTHORIZATION, null, ACCOUNT, List.of(target))
        );
        code("OWNER_TARGET_LIMIT", () ->
            snapshots.capture(AUTHORIZATION, ACTOR, ACCOUNT, List.of())
        );
        code("OWNER_TARGET_LIMIT", () ->
            snapshots.capture(AUTHORIZATION, ACTOR, ACCOUNT, List.of(target, target))
        );
        assertEquals(0, requests.get());
    }

    private SourceBindingSnapshot capture() {
        return snapshots.capture(
            AUTHORIZATION,
            ACTOR,
            ACCOUNT,
            List.of(new SourceBindingSnapshots.Target(ID, 1))
        );
    }

    private void code(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(VclException.class, action).code());
    }

    private ObjectNode description() {
        var definition = MAPPER.createObjectNode()
            .put("id", ID.toString())
            .put("schemaRevision", 1)
            .put("profile", VclSourceBindingProfile.VERSION)
            .put("configurationDigest", "a".repeat(64));
        definition
            .putArray("fields")
            .addObject()
            .put("id", UUID.nameUUIDFromBytes(new byte[] { 1 }).toString())
            .put("label", "Door")
            .put("type", "BOOLEAN")
            .put("nullable", true);
        var value = MAPPER.createObjectNode()
            .put("id", ID.toString())
            .put("accountId", ACCOUNT.toString())
            .put("actorId", ACTOR.toString())
            .put("deviceId", DEVICE.toString())
            .put("schemaRevision", 1)
            .put("currentSchemaRevision", 1)
            .put("snapshotId", SNAPSHOT.toString())
            .put("definitionDigest", VclCanonical.sha256(VclCanonical.encode(definition)))
            .put("accessScopeDigest", "a".repeat(64))
            .put("sourceStateDigest", "a".repeat(64))
            .put("sourceActive", true)
            .put("ruleExecutionReady", false);
        value.set("definition", definition);
        value.set("captureCapabilities", VclSourceBindingProfile.capabilities());
        return value;
    }
}
