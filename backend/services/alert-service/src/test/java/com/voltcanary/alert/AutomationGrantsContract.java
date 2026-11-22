package com.voltcanary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.alert.configurable.port.GrantReviewStore;
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
        "spring.datasource.url=jdbc:h2:mem:automation-grants;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "app.kafka.consume-enabled=false",
        "app.alert.authorization.enabled=false",
        "app.alert.notifications.worker-enabled=false",
        "app.alert.reconciliation.enabled=false",
    }
)
abstract class AutomationGrantsContract extends SignedUserTest {

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

    @MockitoSpyBean
    GrantReviewStore reviews;

    @MockitoSpyBean
    GrantRevocationStore revocations;

    private record Fixture(
        UUID actor,
        GrantOwnerFixture.Binding source,
        GrantOwnerFixture.Dataset dataset,
        String key,
        JsonNode reserved,
        String definition,
        JsonNode prepared
    ) {}

    @Test
    void exactReviewedSourceAndNativeScopeCreatesOnlyAnOwnedPermissionAndStableReceipt() {
        Fixture fixture = fixture();
        var applied = apply(fixture, fixture.prepared());
        assertEquals(HttpStatus.OK, applied.getStatusCode(), applied.getBody().toString());
        assertEquals("SUCCEEDED", applied.getBody().path("status").asText());
        assertEquals(fixture.reserved().path("receiptId"), applied.getBody().path("receiptId"));
        assertEquals("1", applied.getBody().at("/result/createdEpoch").asText());
        assertEquals(applied.getBody(), apply(fixture, fixture.prepared()).getBody());
        var grant = getGrant(fixture);
        assertEquals(HttpStatus.OK, grant.getStatusCode(), grant.getBody().toString());
        assertEquals("ACTIVE", grant.getBody().path("status").asText());
        assertEquals("1", grant.getBody().path("epoch").asText());
        assertEquals(1, grant.getBody().path("effects").size());
        assertEquals("ALERT", grant.getBody().at("/effects/0").asText());
        assertTrue(grant.getBody().path("deliveryChannels").isEmpty());
        var decoded = com.voltcanary.vcl.VclAutomationGrantDescription.parse(
            grant.getBody().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        assertEquals(fixture.actor(), decoded.actorId());
        assertTrue(
            decoded.includes(
                com.voltcanary.vcl.VclMetadata.Resource.BINDING,
                fixture.source().id,
                1,
                fixture.source().boolField
            )
        );
        UUID account = GrantOwnerFixture.OWNERS.get(fixture.actor()).account();
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rules WHERE account_id=?",
                Integer.class,
                account
            )
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE account_id=?",
                Integer.class,
                account
            )
        );
        assertEquals(1, grantCount(fixture));
    }

    @Test
    void changedPreviewInvalidatesOldConsentWithoutChangingReservedIdsOrLease() {
        Fixture fixture = fixture();
        var changed = (ObjectNode) json(fixture.definition());
        (
            (com.fasterxml.jackson.databind.node.ArrayNode) changed.at(
                "/definition/sourceBindings/0/fieldIds"
            )
        ).add(fixture.source().textField.toString());
        var prepared = call(
            fixture.actor(),
            HttpMethod.POST,
            "/alert-rules/configurable/grant-reviews",
            changed.toString()
        );
        assertEquals(HttpStatus.OK, prepared.getStatusCode(), prepared.getBody().toString());
        assertEquals(2, prepared.getBody().path("revision").asLong());
        assertNotEquals(
            fixture.prepared().path("reviewDigest"),
            prepared.getBody().path("reviewDigest")
        );
        assertEquals(fixture.reserved().path("grantId"), prepared.getBody().path("grantId"));
        assertEquals(fixture.reserved().path("expiresAt"), prepared.getBody().path("expiresAt"));
        assertEquals(HttpStatus.CONFLICT, apply(fixture, fixture.prepared()).getStatusCode());
        assertEquals(0, grantCount(fixture));
        assertEquals(HttpStatus.OK, apply(fixture, prepared.getBody()).getStatusCode());
    }

    @Test
    void sourceStateRevisionAndNativeGenerationChangesCannotBeSilentlyRebased() {
        Fixture fixture = fixture();
        fixture.source().state = VclCanonical.sha256("changed");
        assertEquals(HttpStatus.CONFLICT, apply(fixture, fixture.prepared()).getStatusCode());
        fixture.source().state = VclCanonical.sha256("initial-state");
        fixture.source().currentRevision = 2;
        assertEquals(HttpStatus.CONFLICT, apply(fixture, fixture.prepared()).getStatusCode());
        fixture.source().currentRevision = 1;
        fixture.dataset().generation = UUID.randomUUID();
        assertEquals(HttpStatus.CONFLICT, apply(fixture, fixture.prepared()).getStatusCode());
        assertEquals(0, grantCount(fixture));
    }

    @Test
    void changingNativeRowsDoesNotPretendThatGrantMetadataIsARowSnapshot() {
        Fixture fixture = fixture();
        fixture.dataset().dataVersion++;
        assertEquals(HttpStatus.OK, apply(fixture, fixture.prepared()).getStatusCode());
        assertTrue(fixture.prepared().path("readSet").isArray());
        assertTrue(fixture.prepared().path("readSet").get(1).path("snapshotId").isNull());
        assertTrue(fixture.prepared().path("readSet").get(1).path("checkpoint").isNull());
    }

    @Test
    void foreignFieldsUnsupportedInputsAndClaimedAuthorityNeverReachGrantEffects() {
        Fixture fixture = fixture();
        var unknown = (ObjectNode) json(fixture.definition());
        ((ObjectNode) unknown.path("definition")).put("deliveryChannels", "invented");
        int before = GrantOwnerFixture.READS.get();
        assertEquals(
            HttpStatus.BAD_REQUEST,
            call(
                fixture.actor(),
                HttpMethod.POST,
                "/alert-rules/configurable/grant-reviews",
                unknown.toString()
            ).getStatusCode()
        );
        assertEquals(before, GrantOwnerFixture.READS.get());
        var foreign = (ObjectNode) json(fixture.definition());
        ((ObjectNode) foreign.at("/definition/sourceBindings/0"))
            .putArray("fieldIds")
            .add(UUID.randomUUID().toString());
        assertEquals(
            HttpStatus.NOT_FOUND,
            call(
                fixture.actor(),
                HttpMethod.POST,
                "/alert-rules/configurable/grant-reviews",
                foreign.toString()
            ).getStatusCode()
        );
        var other = GrantOwnerFixture.actor();
        assertEquals(
            HttpStatus.NOT_FOUND,
            call(other, HttpMethod.GET, reviewPath(fixture), null).getStatusCode()
        );
        assertEquals(
            HttpStatus.NOT_FOUND,
            call(
                other,
                HttpMethod.POST,
                reviewPath(fixture) + "/apply",
                application(fixture.prepared(), fixture.key())
            ).getStatusCode()
        );
        assertEquals(0, grantCount(fixture));
    }

    @Test
    void invalidExpiryAndChangedApplyKeyCannotExtendOriginalAuthority() {
        Fixture fixture = fixture();
        for (String expiry : List.of(
            "not-an-instant",
            Instant.now().plus(31, ChronoUnit.DAYS).toString(),
            Instant.now().minus(1, ChronoUnit.DAYS).toString()
        )) {
            var changed = (ObjectNode) json(fixture.definition());
            ((ObjectNode) changed.path("definition")).put("expiresAt", expiry);
            assertEquals(
                HttpStatus.UNPROCESSABLE_CONTENT,
                call(
                    fixture.actor(),
                    HttpMethod.POST,
                    "/alert-rules/configurable/grant-reviews",
                    changed.toString()
                ).getStatusCode()
            );
        }
        assertEquals(
            HttpStatus.CONFLICT,
            call(
                fixture.actor(),
                HttpMethod.POST,
                reviewPath(fixture) + "/apply",
                application(fixture.prepared(), "another-key")
            ).getStatusCode()
        );
        jdbc.update(
            "UPDATE cfg_grant_review SET expires_at=? WHERE id=?",
            OffsetDateTime.now().minusDays(1),
            UUID.fromString(fixture.reserved().path("reviewId").asText())
        );
        assertEquals(HttpStatus.CONFLICT, apply(fixture, fixture.prepared()).getStatusCode());
        assertEquals(0, grantCount(fixture));
    }

    @Test
    void lostCreateCommitAcknowledgementReturnsTheOriginalDurableReceipt() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new VclException("GRANT_STORAGE_UNAVAILABLE", "");
        })
            .when(reviews)
            .complete(any(UUID.class), any(UUID.class), any(UUID.class));
        var applied = apply(fixture, fixture.prepared());
        assertEquals(HttpStatus.OK, applied.getStatusCode(), applied.getBody().toString());
        assertEquals("SUCCEEDED", applied.getBody().path("status").asText());
        assertEquals(fixture.reserved().path("receiptId"), applied.getBody().path("receiptId"));
        assertEquals(1, grantCount(fixture));
    }

    @Test
    void unknownCreateReconcilesWithoutDispatchAndExpiredNoEffectIsProvenUnderTheFence() {
        Fixture fixture = fixture();
        doThrow(new VclException("GRANT_STORAGE_UNAVAILABLE", ""))
            .when(reviews)
            .complete(any(UUID.class), any(UUID.class), any(UUID.class));
        var pending = apply(fixture, fixture.prepared());
        assertEquals(HttpStatus.ACCEPTED, pending.getStatusCode());
        assertEquals("2", pending.getHeaders().getFirst("Retry-After"));
        var reconciled = call(
            fixture.actor(),
            HttpMethod.POST,
            reviewPath(fixture) + "/reconcile",
            "{}"
        );
        assertEquals(HttpStatus.ACCEPTED, reconciled.getStatusCode());
        verify(reviews, times(1)).complete(any(UUID.class), any(UUID.class), any(UUID.class));
        assertEquals(0, grantCount(fixture));
        jdbc.update(
            "UPDATE cfg_grant_review SET expires_at=? WHERE id=?",
            OffsetDateTime.now().minusDays(1),
            UUID.fromString(fixture.reserved().path("reviewId").asText())
        );
        var failed = call(
            fixture.actor(),
            HttpMethod.POST,
            reviewPath(fixture) + "/reconcile",
            "{}"
        );
        assertEquals(HttpStatus.CONFLICT, failed.getStatusCode());
        assertEquals("FAILED", failed.getBody().path("status").asText());
        assertEquals("LEASE_EXPIRED_NO_EFFECT", failed.getBody().path("outcomeCode").asText());
    }

    @Test
    void originalUnknownCreateCanRetryOnlyTheSameUnexpiredReviewedIntent() {
        Fixture fixture = fixture();
        doThrow(new VclException("GRANT_STORAGE_UNAVAILABLE", ""))
            .doCallRealMethod()
            .when(reviews)
            .complete(any(UUID.class), any(UUID.class), any(UUID.class));
        var pending = apply(fixture, fixture.prepared());
        var applied = apply(fixture, fixture.prepared());
        assertEquals(HttpStatus.ACCEPTED, pending.getStatusCode());
        assertEquals(HttpStatus.OK, applied.getStatusCode());
        assertEquals(pending.getBody().path("grantId"), applied.getBody().path("grantId"));
        assertEquals(pending.getBody().path("receiptId"), applied.getBody().path("receiptId"));
        assertEquals(1, grantCount(fixture));
    }

    @Test
    void concurrentConfirmedApplyCreatesOneGrantAndOneCreationReceipt() throws Exception {
        Fixture fixture = fixture();
        var first = CompletableFuture.supplyAsync(() -> apply(fixture, fixture.prepared()));
        var second = CompletableFuture.supplyAsync(() -> apply(fixture, fixture.prepared()));
        var a = first.get(15, TimeUnit.SECONDS);
        var b = second.get(15, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK, a.getStatusCode(), a.getBody().toString());
        assertEquals(HttpStatus.OK, b.getStatusCode(), b.getBody().toString());
        assertEquals(a.getBody(), b.getBody());
        assertEquals(1, grantCount(fixture));
    }

    @Test
    void revokeIsMonotoneReplaySafeAndCreationReceiptNeverBecomesCurrentGrantState() {
        Fixture fixture = fixture();
        var created = apply(fixture, fixture.prepared()).getBody();
        String key = UUID.randomUUID().toString();
        String body = revokeBody("1", key);
        var revoked = call(fixture.actor(), HttpMethod.POST, grantPath(fixture) + "/revoke", body);
        assertEquals(HttpStatus.OK, revoked.getStatusCode(), revoked.getBody().toString());
        assertEquals("2", revoked.getBody().path("resultEpoch").asText());
        assertEquals(
            revoked.getBody(),
            call(fixture.actor(), HttpMethod.POST, grantPath(fixture) + "/revoke", body).getBody()
        );
        assertEquals("REVOKED", getGrant(fixture).getBody().path("status").asText());
        assertEquals("2", getGrant(fixture).getBody().path("epoch").asText());
        assertEquals(created, apply(fixture, fixture.prepared()).getBody());
        assertEquals(
            HttpStatus.CONFLICT,
            call(
                fixture.actor(),
                HttpMethod.POST,
                grantPath(fixture) + "/revoke",
                revokeBody("1", "new-key")
            ).getStatusCode()
        );
    }

    @Test
    void revokeRemainsAvailableForTheSignedOwnerAfterAccountOrSourceAccessIsLost() {
        Fixture fixture = fixture();
        apply(fixture, fixture.prepared());
        var owner = GrantOwnerFixture.OWNERS.get(fixture.actor());
        GrantOwnerFixture.OWNERS.put(
            fixture.actor(),
            new GrantOwnerFixture.Owner(owner.account(), "DISABLED")
        );
        fixture.source().active = false;
        assertEquals(HttpStatus.FORBIDDEN, getGrant(fixture).getStatusCode());
        var revoked = call(
            fixture.actor(),
            HttpMethod.POST,
            grantPath(fixture) + "/revoke",
            revokeBody("1", UUID.randomUUID().toString())
        );
        assertEquals(HttpStatus.OK, revoked.getStatusCode(), revoked.getBody().toString());
        assertEquals("2", revoked.getBody().path("resultEpoch").asText());
        assertEquals(
            HttpStatus.OK,
            call(
                fixture.actor(),
                HttpMethod.GET,
                "/alert-rules/configurable/grant-revocations/" +
                    revoked.getBody().path("id").asText(),
                null
            ).getStatusCode()
        );
    }

    @Test
    void lostRevokeAcknowledgementCannotAdvanceEpochTwice() {
        Fixture fixture = fixture();
        apply(fixture, fixture.prepared());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new VclException("GRANT_STORAGE_UNAVAILABLE", "");
        })
            .when(revocations)
            .complete(any(UUID.class), any(UUID.class));
        String body = revokeBody("1", UUID.randomUUID().toString());
        var first = call(fixture.actor(), HttpMethod.POST, grantPath(fixture) + "/revoke", body);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody().toString());
        assertEquals(
            first.getBody(),
            call(fixture.actor(), HttpMethod.POST, grantPath(fixture) + "/revoke", body).getBody()
        );
        assertEquals("2", getGrant(fixture).getBody().path("epoch").asText());
    }

    private Fixture fixture() {
        UUID actor = GrantOwnerFixture.actor();
        var source = new GrantOwnerFixture.Binding(actor);
        var dataset = new GrantOwnerFixture.Dataset(actor);
        var rule = call(
            actor,
            HttpMethod.POST,
            "/alert-rules/configurable/review-reservations",
            GrantOwnerFixture.MAPPER.valueToTree(
                Map.of(
                    "kind",
                    "RULE",
                    "outputKeys",
                    List.of("count"),
                    "idempotencyKey",
                    UUID.randomUUID().toString()
                )
            ).toString()
        );
        assertEquals(HttpStatus.OK, rule.getStatusCode(), rule.getBody().toString());
        String key = UUID.randomUUID().toString();
        var reserved = call(
            actor,
            HttpMethod.POST,
            "/alert-rules/configurable/grant-review-reservations",
            GrantOwnerFixture.MAPPER.valueToTree(
                Map.of(
                    "kind",
                    "AUTOMATION_GRANT",
                    "ruleReviewId",
                    rule.getBody().path("reviewId").asText(),
                    "idempotencyKey",
                    key
                )
            ).toString()
        );
        assertEquals(HttpStatus.OK, reserved.getStatusCode(), reserved.getBody().toString());
        var body = GrantOwnerFixture.MAPPER.createObjectNode().put(
            "reservationId",
            reserved.getBody().path("reviewId").asText()
        );
        var scope = body
            .putObject("definition")
            .put(
                "expiresAt",
                Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS).toString()
            );
        scope
            .putArray("sourceBindings")
            .addObject()
            .put("id", source.id.toString())
            .put("schemaRevision", 1)
            .putArray("fieldIds")
            .add(source.boolField.toString());
        scope
            .putArray("datasets")
            .addObject()
            .put("id", dataset.id.toString())
            .put("schemaRevision", 1)
            .putArray("fieldIds")
            .add(dataset.field.toString());
        var prepared = call(
            actor,
            HttpMethod.POST,
            "/alert-rules/configurable/grant-reviews",
            body.toString()
        );
        assertEquals(HttpStatus.OK, prepared.getStatusCode(), prepared.getBody().toString());
        return new Fixture(
            actor,
            source,
            dataset,
            key,
            reserved.getBody(),
            body.toString(),
            prepared.getBody()
        );
    }

    @Test
    void grantReservationReplayKeepsIdsAndLeaseAndRejectsAnotherParentUnderTheSameKey() {
        Fixture fixture = fixture();
        UUID reviewId = UUID.fromString(fixture.reserved().path("reviewId").asText());
        UUID parent = jdbc.queryForObject(
            "SELECT rule_review_id FROM cfg_grant_review WHERE id=?",
            UUID.class,
            reviewId
        );
        var same = GrantOwnerFixture.MAPPER.valueToTree(
            Map.of(
                "kind",
                "AUTOMATION_GRANT",
                "ruleReviewId",
                parent,
                "idempotencyKey",
                fixture.key()
            )
        ).toString();
        var replay = call(
            fixture.actor(),
            HttpMethod.POST,
            "/alert-rules/configurable/grant-review-reservations",
            same
        );
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(fixture.prepared(), replay.getBody());
        var different = GrantOwnerFixture.MAPPER.valueToTree(
            Map.of(
                "kind",
                "AUTOMATION_GRANT",
                "ruleReviewId",
                UUID.randomUUID(),
                "idempotencyKey",
                fixture.key()
            )
        ).toString();
        assertEquals(
            HttpStatus.CONFLICT,
            call(
                fixture.actor(),
                HttpMethod.POST,
                "/alert-rules/configurable/grant-review-reservations",
                different
            ).getStatusCode()
        );
        UUID account = GrantOwnerFixture.OWNERS.get(fixture.actor()).account();
        for (int index = 0; index < 99; index++) reviews.reserve(
            account,
            fixture.actor(),
            parent,
            "grant-quota-" + index
        );
        var overflow = GrantOwnerFixture.MAPPER.valueToTree(
            Map.of(
                "kind",
                "AUTOMATION_GRANT",
                "ruleReviewId",
                parent,
                "idempotencyKey",
                "grant-overflow"
            )
        ).toString();
        assertEquals(
            HttpStatus.TOO_MANY_REQUESTS,
            call(
                fixture.actor(),
                HttpMethod.POST,
                "/alert-rules/configurable/grant-review-reservations",
                overflow
            ).getStatusCode()
        );
        assertEquals(0, grantCount(fixture));
    }

    @Test
    void failedGrantCompletionRollsBackThePermissionAndPreservesTheOriginalUnknownIntent() {
        Fixture fixture = fixture();
        String constraint = "test_grant_receipt_" + UUID.randomUUID().toString().replace("-", "");
        String reviewId = fixture.reserved().path("reviewId").asText();
        jdbc.execute(
            "ALTER TABLE cfg_grant_review ADD CONSTRAINT " +
                constraint +
                " CHECK (id<>CAST('" +
                reviewId +
                "' AS UUID) OR status<>'SUCCEEDED')"
        );
        try {
            var pending = apply(fixture, fixture.prepared());
            assertEquals(HttpStatus.ACCEPTED, pending.getStatusCode());
            assertEquals("UNKNOWN", pending.getBody().path("status").asText());
            assertEquals(0, grantCount(fixture));
        } finally {
            jdbc.execute("ALTER TABLE cfg_grant_review DROP CONSTRAINT " + constraint);
        }
        var applied = apply(fixture, fixture.prepared());
        assertEquals(HttpStatus.OK, applied.getStatusCode(), applied.getBody().toString());
        assertEquals(fixture.reserved().path("receiptId"), applied.getBody().path("receiptId"));
        assertEquals(1, grantCount(fixture));
    }

    @Test
    void failedRevokeReceiptRollsBackEpochAndSameUnknownIntentCanFinishOnce() {
        Fixture fixture = fixture();
        apply(fixture, fixture.prepared());
        String constraint = "test_grant_revoke_" + UUID.randomUUID().toString().replace("-", "");
        String grantId = fixture.reserved().path("grantId").asText();
        jdbc.execute(
            "ALTER TABLE cfg_grant_revocation ADD CONSTRAINT " +
                constraint +
                " CHECK (grant_id<>CAST('" +
                grantId +
                "' AS UUID) OR status<>'SUCCEEDED')"
        );
        String body = revokeBody("1", UUID.randomUUID().toString());
        JsonNode pending;
        try {
            var response = call(
                fixture.actor(),
                HttpMethod.POST,
                grantPath(fixture) + "/revoke",
                body
            );
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            pending = response.getBody();
            assertEquals("1", getGrant(fixture).getBody().path("epoch").asText());
            assertEquals("ACTIVE", getGrant(fixture).getBody().path("status").asText());
        } finally {
            jdbc.execute("ALTER TABLE cfg_grant_revocation DROP CONSTRAINT " + constraint);
        }
        var revoked = call(fixture.actor(), HttpMethod.POST, grantPath(fixture) + "/revoke", body);
        assertEquals(HttpStatus.OK, revoked.getStatusCode());
        assertEquals(pending.path("receiptId"), revoked.getBody().path("receiptId"));
        assertEquals("2", revoked.getBody().path("resultEpoch").asText());
    }

    @Test
    void concurrentRevocationsOfOneEpochHaveOneWinnerAndNeverRestorePermission() throws Exception {
        Fixture fixture = fixture();
        apply(fixture, fixture.prepared());
        var first = CompletableFuture.supplyAsync(() ->
            call(
                fixture.actor(),
                HttpMethod.POST,
                grantPath(fixture) + "/revoke",
                revokeBody("1", "first-revoke")
            )
        );
        var second = CompletableFuture.supplyAsync(() ->
            call(
                fixture.actor(),
                HttpMethod.POST,
                grantPath(fixture) + "/revoke",
                revokeBody("1", "second-revoke")
            )
        );
        var results = List.of(
            first.get(15, TimeUnit.SECONDS).getStatusCode(),
            second.get(15, TimeUnit.SECONDS).getStatusCode()
        );
        assertEquals(1, results.stream().filter(HttpStatus.OK::equals).count());
        assertEquals(1, results.stream().filter(HttpStatus.CONFLICT::equals).count());
        assertEquals("2", getGrant(fixture).getBody().path("epoch").asText());
        assertEquals("REVOKED", getGrant(fixture).getBody().path("status").asText());
    }

    private int grantCount(Fixture fixture) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM cfg_automation_grant WHERE account_id=?",
            Integer.class,
            GrantOwnerFixture.OWNERS.get(fixture.actor()).account()
        );
    }

    @Test
    void sourceOnlyPermissionDoesNotImplicitlyAcquireDatasetOrDeliveryRights() {
        Fixture fixture = fixture();
        var changed = (ObjectNode) json(fixture.definition());
        ((ObjectNode) changed.path("definition")).putArray("datasets");
        var prepared = call(
            fixture.actor(),
            HttpMethod.POST,
            "/alert-rules/configurable/grant-reviews",
            changed.toString()
        );
        assertEquals(HttpStatus.OK, prepared.getStatusCode(), prepared.getBody().toString());
        assertEquals(HttpStatus.OK, apply(fixture, prepared.getBody()).getStatusCode());
        var grant = getGrant(fixture).getBody();
        assertTrue(grant.at("/definition/datasets").isEmpty());
        assertTrue(grant.path("deliveryChannels").isEmpty());
        assertEquals(1, grant.at("/definition/sourceBindings/0/fieldIds").size());
    }

    @Test
    void unknownCreationWithExpiredPermissionLifetimeIsProvenNoEffectBeforeReviewLeaseEnds() throws Exception {
        Fixture fixture = fixture();
        var changed = (ObjectNode) json(fixture.definition());
        ((ObjectNode) changed.path("definition")).put(
            "expiresAt",
            Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MICROS).toString()
        );
        var prepared = call(
            fixture.actor(),
            HttpMethod.POST,
            "/alert-rules/configurable/grant-reviews",
            changed.toString()
        );
        assertEquals(HttpStatus.OK, prepared.getStatusCode(), prepared.getBody().toString());
        doThrow(new VclException("GRANT_STORAGE_UNAVAILABLE", ""))
            .when(reviews)
            .complete(any(UUID.class), any(UUID.class), any(UUID.class));
        assertEquals(HttpStatus.ACCEPTED, apply(fixture, prepared.getBody()).getStatusCode());
        Thread.sleep(2300);
        var result = call(
            fixture.actor(),
            HttpMethod.POST,
            reviewPath(fixture) + "/reconcile",
            "{}"
        );
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("FAILED", result.getBody().path("status").asText());
        assertEquals("GRANT_EXPIRED_NO_EFFECT", result.getBody().path("outcomeCode").asText());
        assertTrue(
            Instant.parse(result.getBody().path("expiresAt").asText()).isAfter(Instant.now())
        );
        assertEquals(0, grantCount(fixture));
    }

    @Test
    void reviewLeaseThatExpiresWhileWaitingForTheFenceCannotAuthorizeANewDispatch() throws Exception {
        Fixture fixture = fixture();
        UUID account = GrantOwnerFixture.OWNERS.get(fixture.actor()).account();
        UUID id = UUID.fromString(fixture.reserved().path("reviewId").asText());
        jdbc.update(
            "UPDATE cfg_grant_review SET expires_at=? WHERE id=?",
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
                query.setObject(1, account);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            var entered = new java.util.concurrent.CountDownLatch(1);
            var late = CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                return org.junit.jupiter.api.Assertions.assertThrows(VclException.class, () ->
                    reviews.begin(
                        account,
                        fixture.actor(),
                        id,
                        fixture.prepared().path("revision").asLong(),
                        fixture.prepared().path("reviewDigest").asText(),
                        fixture.key()
                    )
                );
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.sleep(1400);
            blocker.commit();
            assertEquals("REVIEW_EXPIRED", late.get(5, TimeUnit.SECONDS).code());
        }
        assertEquals(0, grantCount(fixture));
    }

    @Test
    void grantThatExpiresWhileLookupWaitsForTheFenceIsNotReportedActive() throws Exception {
        Fixture fixture = fixture();
        var changed = (ObjectNode) json(fixture.definition());
        ((ObjectNode) changed.path("definition")).put(
            "expiresAt",
            Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS).toString()
        );
        var prepared = call(
            fixture.actor(),
            HttpMethod.POST,
            "/alert-rules/configurable/grant-reviews",
            changed.toString()
        );
        assertEquals(HttpStatus.OK, prepared.getStatusCode(), prepared.getBody().toString());
        assertEquals(HttpStatus.OK, apply(fixture, prepared.getBody()).getStatusCode());
        UUID account = GrantOwnerFixture.OWNERS.get(fixture.actor()).account();
        UUID id = UUID.fromString(fixture.reserved().path("grantId").asText());
        try (var blocker = database.getConnection()) {
            blocker.setAutoCommit(false);
            try (
                var query = blocker.prepareStatement(
                    "SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE"
                )
            ) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            var entered = new java.util.concurrent.CountDownLatch(1);
            var late = CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                return reviews.grant(account, fixture.actor(), id);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.sleep(1400);
            blocker.commit();
            assertEquals("EXPIRED", late.get(5, TimeUnit.SECONDS).status());
        }
    }

    private ResponseEntity<JsonNode> apply(Fixture fixture, JsonNode prepared) {
        return call(
            fixture.actor(),
            HttpMethod.POST,
            reviewPath(fixture) + "/apply",
            application(prepared, fixture.key())
        );
    }

    private String application(JsonNode prepared, String key) {
        return GrantOwnerFixture.MAPPER.valueToTree(
            Map.of(
                "reviewRevision",
                prepared.path("revision").asLong(),
                "reviewDigest",
                prepared.path("reviewDigest").asText(),
                "idempotencyKey",
                key
            )
        ).toString();
    }

    private String revokeBody(String epoch, String key) {
        return GrantOwnerFixture.MAPPER.valueToTree(
            Map.of("expectedEpoch", epoch, "idempotencyKey", key)
        ).toString();
    }

    private String reviewPath(Fixture fixture) {
        return (
            "/alert-rules/configurable/grant-reviews/" +
            fixture.reserved().path("reviewId").asText()
        );
    }

    private String grantPath(Fixture fixture) {
        return "/alert-rules/configurable/grants/" + fixture.reserved().path("grantId").asText();
    }

    private ResponseEntity<JsonNode> getGrant(Fixture fixture) {
        return call(fixture.actor(), HttpMethod.GET, grantPath(fixture), null);
    }

    private JsonNode json(String value) {
        try {
            return GrantOwnerFixture.MAPPER.readTree(value);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ResponseEntity<JsonNode> call(UUID actor, HttpMethod method, String path, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(
            SignedUserTokens.create(actor, "grant-owner@example.invalid", "USER")
        );
        headers.set("X-User-Id", UUID.randomUUID().toString());
        headers.set("X-Account-Id", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
