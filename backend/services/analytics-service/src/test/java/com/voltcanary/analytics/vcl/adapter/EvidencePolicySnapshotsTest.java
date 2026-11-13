package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclEvidencePolicyDescription;
import com.voltcanary.vcl.VclException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidencePolicySnapshotsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID POLICY = UUID.randomUUID(), ACTOR = UUID.randomUUID(), ACCOUNT = UUID.randomUUID(), RULE = UUID.randomUUID(), GRANT = UUID.randomUUID();
    private static final String AUTH = "Bearer private-policy-credential";
    private HttpServer server;
    private EvidencePolicySnapshots snapshots;
    private AutomationGrantSnapshot grant;
    private ObjectNode originalGrant, originalPolicy;
    private byte[] policyBody;
    private String grantBody, path, authorization;
    private int policyStatus, grantStatus, requests;

    @BeforeEach void start() throws Exception {
        String expiry = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS).toString();
        var scope = JSON.createObjectNode().put("expiresAt", expiry);
        scope.putArray("sourceBindings").addObject().put("id", UUID.randomUUID().toString()).put("schemaRevision", 1).putArray("fieldIds").add(UUID.randomUUID().toString());
        scope.putArray("datasets");
        originalGrant = JSON.createObjectNode().put("id", GRANT.toString()).put("accountId", ACCOUNT.toString()).put("actorId", ACTOR.toString())
                .put("ruleId", RULE.toString()).put("profile", "source-native-fields-grant-1").put("epoch", "1").put("status", "ACTIVE")
                .put("expiresAt", expiry).put("definitionDigest", VclCanonical.sha256(VclCanonical.encode(scope)));
        originalGrant.set("definition", scope); originalGrant.putArray("effects").add("ALERT"); originalGrant.putArray("deliveryChannels");
        var definition = JSON.createObjectNode().put("mode", "PRIVATE_REFERENCES").put("retentionDays", 7).put("maxObservationReferences", 20);
        originalPolicy = JSON.createObjectNode().put("id", POLICY.toString()).put("accountId", ACCOUNT.toString()).put("actorId", ACTOR.toString())
                .put("ruleId", RULE.toString()).put("grantId", GRANT.toString()).put("grantEpoch", "1")
                .put("grantDefinitionDigest", originalGrant.path("definitionDigest").asText()).put("profile", VclEvidencePolicyDescription.PROFILE)
                .put("revision", 1).put("status", "ACTIVE").put("usable", true).put("expiresAt", expiry)
                .put("definitionDigest", VclCanonical.sha256(VclCanonical.encode(definition)));
        originalPolicy.set("definition", definition);
        grantBody = originalGrant.toString(); body(originalPolicy); policyStatus = 200; grantStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++; path = exchange.getRequestURI().toString(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            boolean isPolicy = path.contains("/evidence-policies/");
            byte[] bytes = isPolicy ? policyBody : grantBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(isPolicy ? policyStatus : grantStatus, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var properties = new AnalyticsDownstreamProperties(); properties.getAlertService().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAlertService().setTimeout(Duration.ofSeconds(1));
        snapshots = new EvidencePolicySnapshots(JacksonRestClients.builder(), properties);
        grant = new AutomationGrantSnapshots(JacksonRestClients.builder(), properties).capture(AUTH, ACTOR, ACCOUNT, RULE, GRANT);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void policyPinRenewsBothOwnersAndKeepsPrivateImmutableBudgetMetadata() {
        var captured = capture(); int before = requests;
        assertEquals(POLICY, captured.pin().id());
        assertEquals(1, captured.pin().revision());
        assertEquals(before + 4, requests);
        assertEquals(AUTH, authorization);
        assertEquals("/alert-rules/configurable/grants/" + GRANT, path);
        var decoded = VclEvidencePolicyDescription.parse(policyBody);
        ((ObjectNode) decoded.definition()).put("mode", "PUBLIC");
        assertEquals("PRIVATE_REFERENCES", decoded.definition().path("mode").asText());
        assertFalse(captured.toString().contains("private-policy-credential"));
    }

    @Test void ownerIdentityClosedShapeAndDisclosureExpansionAreRejected() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("id", UUID.randomUUID().toString()), value -> value.put("actorId", UUID.randomUUID().toString()),
                value -> value.put("accountId", UUID.randomUUID().toString()), value -> value.put("ruleId", UUID.randomUUID().toString()),
                value -> value.put("profile", "unqualified"), value -> value.put("revision", 2), value -> value.put("revision", "1"),
                value -> value.put("revision", 1.5), value -> value.put("grantEpoch", 1), value -> value.put("grantEpoch", "01"),
                value -> value.put("grantEpoch", "9223372036854775808"), value -> value.put("usable", "true"),
                value -> value.put("usable", false), value -> value.put("definitionDigest", "b".repeat(64)),
                value -> value.put("expiresAt", "invalid"), value -> value.putArray("recipients"), value -> value.put("ruleExecutionReady", true))) {
            var changed = originalPolicy.deepCopy(); change.accept(changed); body(changed);
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
    }

    @Test void wrongGrantEpochScopeDigestOrExtendedLifetimeCannotBindToAnExistingGrant() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("grantId", UUID.randomUUID().toString()), value -> value.put("grantEpoch", "2"),
                value -> value.put("grantDefinitionDigest", "c".repeat(64)),
                value -> value.put("expiresAt", Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MICROS).toString()))) {
            var changed = originalPolicy.deepCopy(); change.accept(changed); body(changed);
            code("EVIDENCE_POLICY_GRANT_CHANGED", this::capture);
        }
    }

    @Test void policyOrGrantDriftAndRevocationInvalidateCapturedPins() {
        var captured = capture();
        var changed = originalPolicy.deepCopy(); ((ObjectNode) changed.path("definition")).put("retentionDays", 8);
        changed.put("definitionDigest", VclCanonical.sha256(VclCanonical.encode(changed.path("definition")))); body(changed);
        code("EVIDENCE_POLICY_CHANGED", captured::pin);
        body(originalPolicy.deepCopy().put("status", "INACTIVE").put("usable", false));
        code("EVIDENCE_POLICY_INACTIVE", captured::pin);
        body(originalPolicy);
        grantBody = originalGrant.deepCopy().put("status", "REVOKED").put("epoch", "2").toString();
        code("AUTOMATION_GRANT_INACTIVE", captured::pin);
        grantBody = originalGrant.deepCopy().put("epoch", "2").toString();
        code("AUTOMATION_GRANT_CHANGED", captured::pin);
    }

    @Test void malformedBudgetsAndJsonCannotPassEvenWithAnUpdatedDefinitionHash() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("mode", "PUBLIC"), value -> value.put("retentionDays", 0), value -> value.put("retentionDays", 31),
                value -> value.put("retentionDays", 7.5), value -> value.put("maxObservationReferences", "20"),
                value -> value.put("maxObservationReferences", 101), value -> value.put("includeValues", true))) {
            var changed = originalPolicy.deepCopy(); change.accept((ObjectNode) changed.path("definition"));
            changed.put("definitionDigest", VclCanonical.sha256(VclCanonical.encode(changed.path("definition")))); body(changed);
            code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
        for (String wire : List.of(originalPolicy + "{}", " ".repeat(1_048_577),
                originalPolicy.toString().replace("\"usable\":true", "\"usable\":true,\"usable\":true"))) {
            policyBody = wire.getBytes(StandardCharsets.UTF_8); code("OWNER_SNAPSHOT_INVALID", this::capture);
        }
        policyBody = new byte[]{(byte) 0xC3, 0x28}; code("OWNER_SNAPSHOT_INVALID", this::capture);
    }

    @Test void failuresAndRedirectsNeverUseCapturedAuthorityOrLeakBodies() {
        var captured = capture();
        for (int status : List.of(302, 401, 403, 404, 500, 503)) {
            policyStatus = status; policyBody = "private upstream detail".getBytes(StandardCharsets.UTF_8); int before = requests;
            var error = assertThrows(VclException.class, captured::pin);
            assertEquals(status == 401 || status == 403 ? "OWNER_ACCESS_REVOKED"
                    : status == 404 ? "EVIDENCE_POLICY_UNAVAILABLE" : "EVIDENCE_POLICY_OWNER_UNAVAILABLE", error.code());
            assertEquals(before + 1, requests); assertFalse(error.getMessage().contains("private"));
        }
        policyStatus = 200; body(originalPolicy); grantStatus = 403; code("OWNER_ACCESS_REVOKED", captured::pin);
    }

    @Test void missingTrustedTargetOrExpiredPolicyFailsBeforePermissionUse() {
        int before = requests;
        code("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.capture(AUTH, null, ACCOUNT, RULE, POLICY, grant));
        code("OWNER_TARGET_INVALID", () -> snapshots.capture(AUTH, ACTOR, ACCOUNT, RULE, POLICY, null));
        assertEquals(before, requests);
        body(originalPolicy.deepCopy().put("expiresAt", Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS).toString()));
        code("EVIDENCE_POLICY_INACTIVE", this::capture);
    }
    private EvidencePolicySnapshot capture() { return snapshots.capture(AUTH, ACTOR, ACCOUNT, RULE, POLICY, grant); }
    private void body(ObjectNode value) { policyBody = value.toString().getBytes(StandardCharsets.UTF_8); }
    private static void code(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(VclException.class, action).code());
    }
}
