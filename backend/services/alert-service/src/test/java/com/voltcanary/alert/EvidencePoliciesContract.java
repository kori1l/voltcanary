package com.voltcanary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.configurable.application.AutomationGrantReviews;
import com.voltcanary.alert.configurable.port.EvidencePolicyStore;
import com.voltcanary.alert.configurable.port.GrantRevocationStore;
import com.voltcanary.common.test.SignedUserTokens;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:evidence-policies;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "app.kafka.consume-enabled=false",
        "app.alert.authorization.enabled=false",
        "app.alert.notifications.worker-enabled=false",
        "app.alert.reconciliation.enabled=false",
    }
)
abstract class EvidencePoliciesContract extends SignedUserTest {

    @DynamicPropertySource
    static void owner(DynamicPropertyRegistry registry) {
        registry.add(
            "app.alert.authorization.device-service-base-url",
            () -> "http://127.0.0.1:" + GrantOwnerFixture.SERVER.getAddress().getPort()
        );
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    javax.sql.DataSource database;

    @Autowired
    GrantRevocationStore revocations;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @MockitoSpyBean
    EvidencePolicyStore policies;

    @MockitoSpyBean
    AutomationGrantReviews grants;

    record Fixture(
        UUID actor,
        UUID account,
        UUID grant,
        GrantOwnerFixture.Binding source,
        String key,
        JsonNode reserved,
        JsonNode prepared
    ) {}

    @Test
    void reviewedPolicyCreatesOnlyPrivateMetadataAndAnImmutableReceipt() {
        var f = fixture();
        var result = apply(f, f.prepared());
        assertEquals(HttpStatus.OK, result.getStatusCode(), result.getBody().toString());
        assertEquals("SUCCEEDED", result.getBody().path("status").asText());
        assertEquals(f.reserved().path("receiptId"), result.getBody().path("receiptId"));
        assertEquals(result.getBody(), apply(f, f.prepared()).getBody());
        var policy = get(f);
        assertEquals(HttpStatus.OK, policy.getStatusCode(), policy.getBody().toString());
        assertEquals("private-reference-evidence-1", policy.getBody().path("profile").asText());
        assertTrue(policy.getBody().path("usable").asBoolean());
        assertEquals(1, policy.getBody().path("revision").asLong());
        assertEquals("PRIVATE_REFERENCES", policy.getBody().at("/definition/mode").asText());
        assertFalse(policy.getBody().has("recipients"));
        var reads = new com.voltcanary.common.web.ConfigurableOwnerReads(
            com.voltcanary.common.web.JacksonRestClients.builder(),
            "http://localhost:" + port,
            java.time.Duration.ofSeconds(2)
        );
        String authorization =
            "Bearer " + SignedUserTokens.create(f.actor(), "policy-owner@example.invalid", "USER");
        var decoded = com.voltcanary.vcl.VclEvidencePolicyDescription.parse(
            reads.evidencePolicy(
                authorization,
                UUID.fromString(f.reserved().path("policyId").asText()),
                System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
            )
        );
        assertEquals(f.actor(), decoded.actorId());
        assertEquals(f.grant(), decoded.grantId());
        assertEquals(
            policy.getBody().path("definitionDigest").asText(),
            decoded.definitionDigest()
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rules WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
        assertEquals(1, count(f));
    }

    @Test
    void changedPreviewInvalidatesConsentButPreservesReservedIdentityAndLease() {
        var f = fixture();
        var changed = prepare(f, 8, 20);
        assertEquals(2, changed.path("revision").asLong());
        assertNotEquals(f.prepared().path("reviewDigest"), changed.path("reviewDigest"));
        assertEquals(f.reserved().path("policyId"), changed.path("policyId"));
        assertEquals(f.reserved().path("expiresAt"), changed.path("expiresAt"));
        assertEquals(HttpStatus.CONFLICT, apply(f, f.prepared()).getStatusCode());
        assertEquals(0, count(f));
        assertEquals(HttpStatus.OK, apply(f, changed).getStatusCode());
    }

    @Test
    void reservationReplaysIdsAndExpiryAndRejectsChangedKeyPayload() {
        var f = fixture();
        var replay = post(
            f.actor(),
            "/evidence-policy-reservations",
            reservation(f.grant(), "1", f.key())
        );
        assertEquals(f.prepared(), replay.getBody());
        assertEquals(
            HttpStatus.CONFLICT,
            post(
                f.actor(),
                "/evidence-policy-reservations",
                reservation(f.grant(), "2", f.key())
            ).getStatusCode()
        );
        assertEquals(
            HttpStatus.CONFLICT,
            post(
                f.actor(),
                path(f) + "/apply",
                application(f.prepared(), "different-key")
            ).getStatusCode()
        );
        assertEquals(0, count(f));
    }

    @Test
    void malformedBudgetAndExpandedDisclosureFailBeforeOwnerReads() {
        var f = fixture();
        for (String definition : List.of(
            "{\"mode\":\"PUBLIC\",\"retentionDays\":7,\"maxObservationReferences\":20}",
            "{\"mode\":\"PRIVATE_REFERENCES\",\"retentionDays\":0,\"maxObservationReferences\":20}",
            "{\"mode\":\"PRIVATE_REFERENCES\",\"retentionDays\":31,\"maxObservationReferences\":20}",
            "{\"mode\":\"PRIVATE_REFERENCES\",\"retentionDays\":7.5,\"maxObservationReferences\":20}",
            "{\"mode\":\"PRIVATE_REFERENCES\",\"retentionDays\":7,\"maxObservationReferences\":101}",
            "{\"mode\":\"PRIVATE_REFERENCES\",\"retentionDays\":7,\"maxObservationReferences\":20,\"recipients\":[]}"
        )) {
            int before = GrantOwnerFixture.READS.get();
            var result = post(
                f.actor(),
                "/evidence-policy-reviews",
                "{\"reservationId\":\"" +
                    f.reserved().path("reviewId").asText() +
                    "\",\"definition\":" +
                    definition +
                    "}"
            );
            assertEquals(
                HttpStatus.BAD_REQUEST,
                result.getStatusCode(),
                result.getBody().toString()
            );
            assertEquals(before, GrantOwnerFixture.READS.get());
        }
        assertEquals(0, count(f));
    }

    @Test
    void freshSourceDriftCannotAuthorizePolicyCreation() {
        var f = fixture();
        f.source().state = VclCanonical.sha256("changed-source-state");
        assertEquals(HttpStatus.CONFLICT, apply(f, f.prepared()).getStatusCode());
        assertEquals(0, count(f));
    }

    @Test
    void grantRevocationRemovesPolicyUsabilityWithoutRewritingCreationReceipt() {
        var f = fixture();
        var created = apply(f, f.prepared()).getBody();
        revoke(f);
        var current = get(f);
        assertEquals(HttpStatus.OK, current.getStatusCode());
        assertFalse(current.getBody().path("usable").asBoolean());
        assertEquals("INACTIVE", current.getBody().path("status").asText());
        assertEquals("1", current.getBody().path("grantEpoch").asText());
        assertEquals(created, apply(f, f.prepared()).getBody());
    }

    @Test
    void revokeBeforeConfirmationCreatesNoPolicy() {
        var f = fixture();
        revoke(f);
        assertEquals(HttpStatus.CONFLICT, apply(f, f.prepared()).getStatusCode());
        assertEquals(0, count(f));
    }

    @Test
    void unknownOutcomeReconciliationDoesNotDispatchAndExactRetryKeepsTheReceipt() {
        var f = fixture();
        doThrow(new VclException("GRANT_STORAGE_UNAVAILABLE", ""))
            .when(policies)
            .complete(any(), any(), any());
        var pending = apply(f, f.prepared());
        assertEquals(HttpStatus.ACCEPTED, pending.getStatusCode());
        var polled = post(f.actor(), path(f) + "/reconcile", "{}");
        assertEquals(HttpStatus.ACCEPTED, polled.getStatusCode());
        assertEquals(pending.getBody().path("receiptId"), polled.getBody().path("receiptId"));
        verify(policies, times(1)).complete(any(), any(), any());
        assertEquals(0, count(f));
        doCallRealMethod().when(policies).complete(any(), any(), any());
        assertEquals(HttpStatus.OK, apply(f, f.prepared()).getStatusCode());
        assertEquals(1, count(f));
    }

    @Test
    void lostCommitAcknowledgementResolvesToTheOriginalSuccessfulReceipt() {
        var f = fixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new VclException("GRANT_STORAGE_UNAVAILABLE", "");
        })
            .when(policies)
            .complete(any(), any(), any());
        var result = apply(f, f.prepared());
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("SUCCEEDED", result.getBody().path("status").asText());
        assertEquals(f.reserved().path("receiptId"), result.getBody().path("receiptId"));
        assertEquals(1, count(f));
    }

    @Test
    void failedSuccessReceiptRollsBackPolicyAndSameUnknownIntentCanFinish() {
        var f = fixture();
        String constraint = "test_policy_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
            "ALTER TABLE cfg_evidence_policy_review ADD CONSTRAINT " +
                constraint +
                " CHECK(id<>CAST('" +
                f.reserved().path("reviewId").asText() +
                "' AS UUID) OR status<>'SUCCEEDED')"
        );
        try {
            assertEquals(HttpStatus.ACCEPTED, apply(f, f.prepared()).getStatusCode());
            assertEquals(0, count(f));
        } finally {
            jdbc.execute("ALTER TABLE cfg_evidence_policy_review DROP CONSTRAINT " + constraint);
        }
        assertEquals(HttpStatus.OK, apply(f, f.prepared()).getStatusCode());
        assertEquals(1, count(f));
    }

    @Test
    void concurrentConfirmedApplyHasOneResourceAndOneImmutableReceipt() throws Exception {
        var f = fixture();
        var first = CompletableFuture.supplyAsync(() -> apply(f, f.prepared()));
        var second = CompletableFuture.supplyAsync(() -> apply(f, f.prepared()));
        var a = first.get(10, TimeUnit.SECONDS);
        var b = second.get(10, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK, a.getStatusCode(), a.getBody().toString());
        assertEquals(a.getBody(), b.getBody());
        assertEquals(1, count(f));
    }

    @Test
    void revokeBetweenDurableMarkerAndEffectIsProvenNoEffectByReconciliation() {
        var f = fixture();
        doAnswer(invocation -> {
            revoke(f);
            return invocation.callRealMethod();
        })
            .when(policies)
            .complete(eq(f.account()), eq(f.actor()), any());
        assertEquals(HttpStatus.ACCEPTED, apply(f, f.prepared()).getStatusCode());
        var result = post(f.actor(), path(f) + "/reconcile", "{}");
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("FAILED", result.getBody().path("status").asText());
        assertEquals("GRANT_CHANGED_NO_EFFECT", result.getBody().path("outcomeCode").asText());
        assertEquals(0, count(f));
    }

    @Test
    void revokedEpochAfterRemoteOwnerReadCannotBeReturnedAsUsable() {
        var f = fixture();
        apply(f, f.prepared());
        doAnswer(invocation -> {
            var old = invocation.callRealMethod();
            revoke(f);
            return old;
        })
            .when(grants)
            .grant(eq(f.actor()), any(), eq(f.grant()));
        assertFalse(get(f).getBody().path("usable").asBoolean());
    }

    @Test
    void foreignScopeAndRevokedAccountCannotInspectPrivateReviewOrPolicy() {
        var f = fixture();
        apply(f, f.prepared());
        UUID foreign = GrantOwnerFixture.actor();
        assertEquals(
            HttpStatus.NOT_FOUND,
            call(foreign, HttpMethod.GET, path(f), null).getStatusCode()
        );
        assertEquals(
            HttpStatus.NOT_FOUND,
            call(
                foreign,
                HttpMethod.GET,
                "/evidence-policies/" + f.reserved().path("policyId").asText(),
                null
            ).getStatusCode()
        );
        GrantOwnerFixture.OWNERS.put(
            f.actor(),
            new GrantOwnerFixture.Owner(f.account(), "DISABLED")
        );
        assertEquals(HttpStatus.FORBIDDEN, get(f).getStatusCode());
    }

    @Test
    void expiredUnknownIntentIsProvenNoEffectWithoutDispatch() {
        var f = fixture();
        doThrow(new VclException("GRANT_STORAGE_UNAVAILABLE", ""))
            .when(policies)
            .complete(any(), any(), any());
        assertEquals(HttpStatus.ACCEPTED, apply(f, f.prepared()).getStatusCode());
        jdbc.update(
            "UPDATE cfg_evidence_policy_review SET expires_at=? WHERE id=?",
            OffsetDateTime.now().minusSeconds(1),
            UUID.fromString(f.reserved().path("reviewId").asText())
        );
        var result = post(f.actor(), path(f) + "/reconcile", "{}");
        assertEquals("LEASE_EXPIRED_NO_EFFECT", result.getBody().path("outcomeCode").asText());
        verify(policies, times(1)).complete(any(), any(), any());
        assertEquals(0, count(f));
    }

    @Test
    void leaseExpiryDuringAccountFenceWaitCannotAuthorizeADispatch() throws Exception {
        var f = fixture();
        UUID id = UUID.fromString(f.reserved().path("reviewId").asText());
        jdbc.update(
            "UPDATE cfg_evidence_policy_review SET expires_at=? WHERE id=?",
            OffsetDateTime.now().plusSeconds(1),
            id
        );
        try (var blocker = database.getConnection()) {
            blocker.setAutoCommit(false);
            try (
                var query = blocker.prepareStatement(
                    "SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE"
                )
            ) {
                query.setObject(1, f.account());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            var entered = new java.util.concurrent.CountDownLatch(1);
            var late = CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                return assertThrows(VclException.class, () ->
                    policies.begin(
                        f.account(),
                        f.actor(),
                        id,
                        f.prepared().path("revision").asLong(),
                        f.prepared().path("reviewDigest").asText(),
                        f.key()
                    )
                );
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.sleep(1400);
            blocker.commit();
            assertEquals("REVIEW_EXPIRED", late.get(5, TimeUnit.SECONDS).code());
        }
        assertEquals(0, count(f));
    }

    @Test
    void corruptStoredPolicyCannotBeReconstructedOrAdvertisedAsUsable() {
        var f = fixture();
        apply(f, f.prepared());
        jdbc.update(
            "UPDATE cfg_evidence_policy SET canonical_definition='{}' WHERE id=?",
            UUID.fromString(f.reserved().path("policyId").asText())
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, get(f).getStatusCode());
    }

    @Test
    void reservationQuotaDoesNotMintAnOverflowIdentityAndExpiredReplayKeepsIds() {
        var f = fixture();
        for (int i = 0; i < 99; i++) policies.reserve(
            f.account(),
            f.actor(),
            f.grant(),
            1,
            "policy-quota-" + i
        );
        assertEquals(
            HttpStatus.TOO_MANY_REQUESTS,
            post(
                f.actor(),
                "/evidence-policy-reservations",
                reservation(f.grant(), "1", "overflow")
            ).getStatusCode()
        );
        UUID id = UUID.fromString(f.reserved().path("reviewId").asText());
        jdbc.update(
            "UPDATE cfg_evidence_policy_review SET expires_at=? WHERE id=?",
            OffsetDateTime.now().minusSeconds(1),
            id
        );
        var replay = post(
            f.actor(),
            "/evidence-policy-reservations",
            reservation(f.grant(), "1", f.key())
        ).getBody();
        assertEquals(f.reserved().path("policyId"), replay.path("policyId"));
        assertEquals("EXPIRED", replay.path("status").asText());
    }

    private Fixture fixture() {
        return fixture(3600);
    }

    @Test
    void reservationLeaseCannotOutliveGrantAndExpiredGrantMakesPolicyUnusable() {
        var f = fixture(300);
        var grantExpiry = jdbc
            .queryForObject(
                "SELECT expires_at FROM cfg_automation_grant WHERE id=?",
                OffsetDateTime.class,
                f.grant()
            )
            .toInstant();
        assertEquals(grantExpiry, Instant.parse(f.reserved().path("expiresAt").asText()));
        var receipt = apply(f, f.prepared()).getBody();
        jdbc.update(
            "UPDATE cfg_automation_grant SET expires_at=? WHERE id=?",
            OffsetDateTime.now().minusSeconds(1),
            f.grant()
        );
        assertFalse(get(f).getBody().path("usable").asBoolean());
        assertEquals(receipt, apply(f, f.prepared()).getBody());
        assertEquals(
            receipt,
            post(
                f.actor(),
                "/evidence-policy-reservations",
                reservation(f.grant(), "1", f.key())
            ).getBody()
        );
        assertEquals(
            HttpStatus.CONFLICT,
            post(
                f.actor(),
                "/evidence-policy-reservations",
                reservation(f.grant(), "1", "new-expired-intent")
            ).getStatusCode()
        );
    }

    @Test
    void identicalConcurrentUnicodeReservationsReturnOneIdentityAndOriginalLease() throws Exception {
        var f = fixture();
        String body = reservation(f.grant(), "1", "политика🙂");
        var first = CompletableFuture.supplyAsync(() ->
            post(f.actor(), "/evidence-policy-reservations", body)
        );
        var second = CompletableFuture.supplyAsync(() ->
            post(f.actor(), "/evidence-policy-reservations", body)
        );
        var a = first.get(10, TimeUnit.SECONDS);
        var b = second.get(10, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK, a.getStatusCode());
        assertEquals(a.getBody(), b.getBody());
        assertEquals(0, count(f));
    }

    private Fixture fixture(long grantLifetimeSeconds) {
        UUID actor = GrantOwnerFixture.actor();
        var source = new GrantOwnerFixture.Binding(actor);
        var rule = post(
            actor,
            "/review-reservations",
            json(
                Map.of(
                    "kind",
                    "RULE",
                    "outputKeys",
                    List.of(),
                    "idempotencyKey",
                    UUID.randomUUID().toString()
                )
            )
        ).getBody();
        String grantKey = UUID.randomUUID().toString();
        var grant = post(
            actor,
            "/grant-review-reservations",
            json(
                Map.of(
                    "kind",
                    "AUTOMATION_GRANT",
                    "ruleReviewId",
                    rule.path("reviewId").asText(),
                    "idempotencyKey",
                    grantKey
                )
            )
        ).getBody();
        var body = GrantOwnerFixture.MAPPER.createObjectNode().put(
            "reservationId",
            grant.path("reviewId").asText()
        );
        var scope = body
            .putObject("definition")
            .put(
                "expiresAt",
                Instant.now()
                    .plusSeconds(grantLifetimeSeconds)
                    .truncatedTo(ChronoUnit.MICROS)
                    .toString()
            );
        scope
            .putArray("sourceBindings")
            .addObject()
            .put("id", source.id.toString())
            .put("schemaRevision", 1)
            .putArray("fieldIds")
            .add(source.boolField.toString());
        scope.putArray("datasets");
        var preparedGrant = post(actor, "/grant-reviews", body.toString()).getBody();
        var appliedGrant = post(
            actor,
            "/grant-reviews/" + grant.path("reviewId").asText() + "/apply",
            application(preparedGrant, grantKey)
        );
        assertEquals(
            HttpStatus.OK,
            appliedGrant.getStatusCode(),
            appliedGrant.getBody().toString()
        );
        UUID grantId = UUID.fromString(grant.path("grantId").asText());
        String key = UUID.randomUUID().toString();
        var reserved = post(actor, "/evidence-policy-reservations", reservation(grantId, "1", key));
        assertEquals(HttpStatus.OK, reserved.getStatusCode(), reserved.getBody().toString());
        var fixture = new Fixture(
            actor,
            GrantOwnerFixture.OWNERS.get(actor).account(),
            grantId,
            source,
            key,
            reserved.getBody(),
            null
        );
        return new Fixture(
            actor,
            fixture.account(),
            grantId,
            source,
            key,
            reserved.getBody(),
            prepare(fixture, 7, 20)
        );
    }

    private JsonNode prepare(Fixture f, int days, int refs) {
        var response = post(
            f.actor(),
            "/evidence-policy-reviews",
            json(
                Map.of(
                    "reservationId",
                    f.reserved().path("reviewId").asText(),
                    "definition",
                    Map.of(
                        "mode",
                        "PRIVATE_REFERENCES",
                        "retentionDays",
                        days,
                        "maxObservationReferences",
                        refs
                    )
                )
            )
        );
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody().toString());
        return response.getBody();
    }

    private String reservation(UUID grant, String epoch, String key) {
        return json(
            Map.of(
                "kind",
                "EVIDENCE_POLICY",
                "grantId",
                grant,
                "expectedGrantEpoch",
                epoch,
                "idempotencyKey",
                key
            )
        );
    }

    private String application(JsonNode view, String key) {
        return json(
            Map.of(
                "reviewRevision",
                view.path("revision").asLong(),
                "reviewDigest",
                view.path("reviewDigest").asText(),
                "idempotencyKey",
                key
            )
        );
    }

    private String path(Fixture f) {
        return "/evidence-policy-reviews/" + f.reserved().path("reviewId").asText();
    }

    private int count(Fixture f) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM cfg_evidence_policy WHERE account_id=?",
            Integer.class,
            f.account()
        );
    }

    private void revoke(Fixture f) {
        var reserved = revocations.reserve(f.actor(), f.grant(), 1, "revoke-" + f.key());
        revocations.complete(f.actor(), reserved.id());
    }

    private ResponseEntity<JsonNode> get(Fixture f) {
        return call(
            f.actor(),
            HttpMethod.GET,
            "/evidence-policies/" + f.reserved().path("policyId").asText(),
            null
        );
    }

    private ResponseEntity<JsonNode> apply(Fixture f, JsonNode prepared) {
        return post(f.actor(), path(f) + "/apply", application(prepared, f.key()));
    }

    private String json(Object value) {
        return GrantOwnerFixture.MAPPER.valueToTree(value).toString();
    }

    private ResponseEntity<JsonNode> post(UUID actor, String path, String body) {
        return call(actor, HttpMethod.POST, path, body);
    }

    private ResponseEntity<JsonNode> call(UUID actor, HttpMethod method, String path, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(
            SignedUserTokens.create(actor, "policy-owner@example.invalid", "USER")
        );
        headers.set("X-User-Id", UUID.randomUUID().toString());
        headers.set("X-Account-Id", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
            "/alert-rules/configurable" + path,
            method,
            new HttpEntity<>(body, headers),
            JsonNode.class
        );
    }
}
