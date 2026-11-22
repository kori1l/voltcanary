package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclAutomationGrantDescription;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
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

class AutomationGrantSnapshotsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ID = UUID.randomUUID(),
        ACTOR = UUID.randomUUID(),
        ACCOUNT = UUID.randomUUID(),
        RULE = UUID.randomUUID(),
        SOURCE = UUID.randomUUID(),
        FIELD = UUID.randomUUID(),
        DATASET = UUID.randomUUID(),
        COLUMN = UUID.randomUUID();
    private static final String AUTH = "Bearer private-grant-credential";
    private HttpServer server;
    private AutomationGrantSnapshots snapshots;
    private ObjectNode description;
    private String body, authorization, path;
    private int status, requests;

    @BeforeEach
    void start() throws Exception {
        description = description();
        body = description.toString();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++;
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            path = exchange.getRequestURI().toString();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        var properties = new AnalyticsDownstreamProperties();
        properties
            .getAlertService()
            .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAlertService().setTimeout(Duration.ofSeconds(1));
        snapshots = new AutomationGrantSnapshots(JacksonRestClients.builder(), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void exactPermissionPinsNeverExpandToOtherFieldsRevisionsOrEffects() {
        var snapshot = capture();
        snapshot.require(VclMetadata.Resource.BINDING, SOURCE, 2, FIELD);
        snapshot.require(VclMetadata.Resource.DATASET, DATASET, 3, COLUMN);
        assertEquals(AUTH, authorization);
        assertEquals("/alert-rules/configurable/grants/" + ID, path);
        assertEquals(ID, snapshot.pin().id());
        assertEquals(1, snapshot.pin().revision());
        code("AUTOMATION_GRANT_SCOPE_DENIED", () ->
            snapshot.require(VclMetadata.Resource.BINDING, SOURCE, 1, FIELD)
        );
        code("AUTOMATION_GRANT_SCOPE_DENIED", () ->
            snapshot.require(VclMetadata.Resource.BINDING, SOURCE, 2, COLUMN)
        );
        code("AUTOMATION_GRANT_SCOPE_DENIED", () ->
            snapshot.require(VclMetadata.Resource.EVIDENCE_POLICY, SOURCE, 2, FIELD)
        );
        assertFalse(snapshot.toString().contains("private"));
    }

    @Test
    void malformedOwnerIdentityEpochAndAuthorityClaimsFailClosed() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
            value -> value.put("id", UUID.randomUUID().toString()),
            value -> value.put("actorId", UUID.randomUUID().toString()),
            value -> value.put("accountId", UUID.randomUUID().toString()),
            value -> value.put("ruleId", UUID.randomUUID().toString()),
            value -> value.put("epoch", 1),
            value -> value.put("epoch", "01"),
            value -> value.put("epoch", "9223372036854775808"),
            value -> value.put("profile", "other"),
            value -> value.put("status", "READY"),
            value -> value.put("expiresAt", "invalid"),
            value -> value.put("definitionDigest", "0".repeat(64)),
            value -> value.putArray("deliveryChannels").add(UUID.randomUUID().toString()),
            value -> value.putArray("effects").add("DELIVERY"),
            value -> value.put("approved", true)
        )) {
            var changed = description.deepCopy();
            change.accept(changed);
            body = changed.toString();
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
    }

    @Test
    void changedEpochScopeAndRevocationInvalidateEveryCapturedRead() {
        var snapshot = capture();
        body = description.deepCopy().put("epoch", "2").toString();
        code("AUTOMATION_GRANT_CHANGED", snapshot::pin);
        body = description.deepCopy().put("status", "REVOKED").put("epoch", "2").toString();
        code("AUTOMATION_GRANT_INACTIVE", snapshot::pin);
        body = description.deepCopy().put("status", "EXPIRED").toString();
        code("AUTOMATION_GRANT_INACTIVE", snapshot::pin);
        var changed = description.deepCopy();
        ((ObjectNode) changed.at("/definition/sourceBindings/0"))
            .putArray("fieldIds")
            .add(COLUMN.toString());
        changed.put(
            "definitionDigest",
            VclCanonical.sha256(VclCanonical.encode(changed.path("definition")))
        );
        body = changed.toString();
        code("AUTOMATION_GRANT_CHANGED", () ->
            snapshot.require(VclMetadata.Resource.BINDING, SOURCE, 2, FIELD)
        );
    }

    @Test
    void strictCodecRejectsMalformedJsonAndScopeShapesEvenWithCorrectDigest() {
        for (String malformed : List.of(
            description.toString() + "{}",
            " ".repeat(1_048_577),
            description.toString().replace("\"epoch\":\"1\"", "\"epoch\":\"1\",\"epoch\":\"1\"")
        )) {
            body = malformed;
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
            value -> value.putArray("sourceBindings"),
            value -> value.put("expiresAt", "invalid"),
            value -> ((ObjectNode) value.path("sourceBindings").get(0)).put("schemaRevision", 1.5),
            value ->
                ((ObjectNode) value.path("sourceBindings").get(0))
                    .putArray("fieldIds")
                    .add(FIELD.toString())
                    .add(FIELD.toString()),
            value -> ((ObjectNode) value.path("sourceBindings").get(0)).put("allFields", true)
        )) {
            var changed = description.deepCopy();
            change.accept((ObjectNode) changed.path("definition"));
            changed.put(
                "definitionDigest",
                VclCanonical.sha256(VclCanonical.encode(changed.path("definition")))
            );
            body = changed.toString();
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
        var decoded = VclAutomationGrantDescription.parse(
            description.toString().getBytes(StandardCharsets.UTF_8)
        );
        ((ObjectNode) decoded.definition()).put("expiresAt", "invalid");
        assertEquals(description.path("definition"), decoded.definition());
    }

    @Test
    void ownerFailureRedirectAndMissingSignedIdentityNeverYieldAuthority() {
        for (int response : List.of(302, 401, 403, 404, 409, 500)) {
            status = response;
            body = "private upstream details";
            int before = requests;
            var failure = assertThrows(VclException.class, this::capture);
            assertEquals(
                response == 401 || response == 403
                    ? "OWNER_ACCESS_REVOKED"
                    : response == 404
                      ? "AUTOMATION_GRANT_UNAVAILABLE"
                      : "AUTOMATION_GRANT_OWNER_UNAVAILABLE",
                failure.code()
            );
            assertEquals(before + 1, requests);
            assertFalse(failure.getMessage().contains("private"));
        }
        int before = requests;
        code("OWNER_AUTHENTICATION_REQUIRED", () ->
            snapshots.capture(null, ACTOR, ACCOUNT, RULE, ID)
        );
        code("OWNER_AUTHENTICATION_REQUIRED", () ->
            snapshots.capture(AUTH, null, ACCOUNT, RULE, ID)
        );
        code("OWNER_TARGET_INVALID", () -> snapshots.capture(AUTH, ACTOR, ACCOUNT, null, ID));
        assertEquals(before, requests);
    }

    private AutomationGrantSnapshot capture() {
        return snapshots.capture(AUTH, ACTOR, ACCOUNT, RULE, ID);
    }

    @Test
    void boundedFieldBatchRenewsOwnerOnceAndRejectsAnyUnlistedField() {
        var snapshot = capture();
        int before = requests;
        snapshot.requireFields(
            com.voltcanary.vcl.VclMetadata.Resource.BINDING,
            SOURCE,
            2,
            List.of(FIELD)
        );
        assertEquals(before + 1, requests);
        code("AUTOMATION_GRANT_SCOPE_DENIED", () ->
            snapshot.requireFields(
                com.voltcanary.vcl.VclMetadata.Resource.BINDING,
                SOURCE,
                2,
                List.of(FIELD, UUID.randomUUID())
            )
        );
        before = requests;
        code("AUTOMATION_GRANT_SCOPE_DENIED", () ->
            snapshot.requireFields(
                com.voltcanary.vcl.VclMetadata.Resource.BINDING,
                SOURCE,
                2,
                List.of()
            )
        );
        code("AUTOMATION_GRANT_SCOPE_DENIED", () ->
            snapshot.requireFields(
                com.voltcanary.vcl.VclMetadata.Resource.BINDING,
                SOURCE,
                2,
                java.util.Collections.nCopies(65, FIELD)
            )
        );
        assertEquals(before, requests);
        status = 403;
        code("OWNER_ACCESS_REVOKED", () ->
            snapshot.requireFields(
                com.voltcanary.vcl.VclMetadata.Resource.BINDING,
                SOURCE,
                2,
                List.of(FIELD)
            )
        );
    }

    private static void code(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(VclException.class, action).code());
    }

    private static ObjectNode description() {
        String expiry = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS).toString();
        var scope = JSON.createObjectNode().put("expiresAt", expiry);
        scope
            .putArray("sourceBindings")
            .addObject()
            .put("id", SOURCE.toString())
            .put("schemaRevision", 2)
            .putArray("fieldIds")
            .add(FIELD.toString());
        scope
            .putArray("datasets")
            .addObject()
            .put("id", DATASET.toString())
            .put("schemaRevision", 3)
            .putArray("fieldIds")
            .add(COLUMN.toString());
        var value = JSON.createObjectNode()
            .put("id", ID.toString())
            .put("actorId", ACTOR.toString())
            .put("accountId", ACCOUNT.toString())
            .put("ruleId", RULE.toString())
            .put("profile", VclAutomationGrantDescription.PROFILE)
            .put("epoch", "1")
            .put("status", "ACTIVE")
            .put("expiresAt", expiry)
            .put("definitionDigest", VclCanonical.sha256(VclCanonical.encode(scope)));
        value.set("definition", scope);
        value.putArray("effects").add("ALERT");
        value.putArray("deliveryChannels");
        return value;
    }
}
