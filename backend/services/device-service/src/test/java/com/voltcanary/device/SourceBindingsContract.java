package com.voltcanary.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.common.test.SignedUserTokens;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.DataSourceType;
import com.voltcanary.device.dto.DataSourceRequest;
import com.voltcanary.device.dto.DeviceRequest;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.service.DataSourceService;
import com.voltcanary.device.service.DeviceService;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.kafka.publish-enabled=false",
        "app.kafka.consume-enabled=false",
    }
)
abstract class SourceBindingsContract extends SignedUserTest {

    @org.springframework.test.context.DynamicPropertySource
    static void captureOwner(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add(
            "app.source-capture.telemetry-service-base-url",
            () -> "http://127.0.0.1:" + CaptureJournalFixture.SERVER.getAddress().getPort()
        );
    }

    @org.junit.jupiter.api.BeforeEach
    void resetCaptureOwner() {
        CaptureJournalFixture.reset();
    }

    @Autowired
    DeviceService devices;

    @Autowired
    DataSourceService sources;

    @Autowired
    AccountService accounts;

    @Autowired
    SourceBindingCatalog catalog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Autowired
    com.voltcanary.device.repository.DataSourceRepository sourceRows;

    @Autowired
    com.voltcanary.device.event.DeviceOutboxService outbox;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private record Fixture(AuthenticatedUser actor, UUID device, UUID source, String external) {}

    @Test
    void coverageReadChecksSourceOwnershipBeforeAndAfterTheTelemetryCall() {
        var f = fixture();
        var account = accounts.findByUserId(f.actor().id()).orElseThrow().getId();
        var pin = catalog.publication(account, f.source()).orElseThrow().pin();
        var headers = new HttpHeaders();
        headers.setBearerAuth(
            SignedUserTokens.create(f.actor().id(), f.actor().email(), f.actor().role())
        );
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(mapper.valueToTree(pin).toString(), headers);
        String path = "/data/source-bindings/" + f.source() + "/coverage/" + UUID.randomUUID();
        var response = http.exchange(path, HttpMethod.POST, entity, JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody().toString());
        assertEquals("PROOF_UNAVAILABLE", response.getBody().path("reason").textValue());
        CaptureJournalFixture.AFTER_READ.set(() ->
            accounts.syncUser(f.actor().id(), f.actor().email(), AccountStatus.DISABLED)
        );
        assertEquals(
            HttpStatus.FORBIDDEN,
            http.exchange(path, HttpMethod.POST, entity, JsonNode.class).getStatusCode()
        );
    }

    @Test
    void actualSignedOwnerResponseRoundTripsThroughTheSharedHttpTransportAndCodec() {
        Fixture fixture = fixture();
        var reads = new ConfigurableOwnerReads(
            JacksonRestClients.builder(),
            "http://localhost:" + port,
            Duration.ofSeconds(2)
        );
        String authorization =
            "Bearer " +
            SignedUserTokens.create(
                fixture.actor().id(),
                fixture.actor().email(),
                fixture.actor().role()
            );
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var actual = VclSourceBindingDescription.parse(
            reads.sourceBinding(authorization, fixture.source(), 1, deadline)
        );
        assertEquals(catalog.describe(fixture.actor().id(), fixture.source(), 1L), actual);
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) actual.definition();
        copy.put("profile", "tampered");
        assertEquals("legacy-source-schema-1", actual.definition().path("profile").asText());
        accounts.syncUser(fixture.actor().id(), fixture.actor().email(), AccountStatus.DISABLED);
        assertEquals(
            "OWNER_ACCESS_REVOKED",
            assertThrows(ConfigurableOwnerReads.Failure.class, () ->
                reads.sourceBinding(authorization, fixture.source(), 1, deadline)
            ).code()
        );
    }

    @Test
    void signedMappingPreviewUsesExactTokensAndCurrentSourceRevision() {
        var f = fixture();
        var schema = catalog.describe(f.actor().id(), f.source(), 1L);
        var request = mapper
            .createObjectNode()
            .put("schemaRevision", 1)
            .put("fieldId", schema.definition().at("/fields/1/id").textValue())
            .put("sample", "on");
        var conversion = request.putObject("conversion").put("method", "parse_boolean");
        conversion.putArray("trueTokens").add("on");
        conversion.putArray("falseTokens").add("off");
        var headers = new HttpHeaders();
        headers.setBearerAuth(
            SignedUserTokens.create(f.actor().id(), f.actor().email(), f.actor().role())
        );
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String path = "/data/source-bindings/" + f.source() + "/mapping-preview";
        var first = http.exchange(
            path,
            HttpMethod.POST,
            new HttpEntity<>(request.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody().toString());
        assertEquals("BOOLEAN", first.getBody().at("/literal/type").textValue());
        assertTrue(first.getBody().at("/literal/value").booleanValue());
        assertEquals(
            schema.snapshotId().toString(),
            first.getBody().path("schemaSnapshotId").textValue()
        );
        request.put("sample", "ON");
        assertEquals(
            "BOOLEAN_TOKEN_UNKNOWN",
            http
                .exchange(
                    path,
                    HttpMethod.POST,
                    new HttpEntity<>(request.toString(), headers),
                    JsonNode.class
                )
                .getBody()
                .path("code")
                .textValue()
        );
        request.put("sample", "on");
        conversion.putArray("falseTokens").add("on");
        assertEquals(
            "BOOLEAN_TOKEN_OVERLAP",
            http
                .exchange(
                    path,
                    HttpMethod.POST,
                    new HttpEntity<>(request.toString(), headers),
                    JsonNode.class
                )
                .getBody()
                .path("code")
                .textValue()
        );
        conversion.putArray("falseTokens").add("off");
        sources.update(
            f.actor(),
            f.device(),
            f.source(),
            request(f.external(), "switch.changed", true)
        );
        assertEquals(
            HttpStatus.CONFLICT,
            http
                .exchange(
                    path,
                    HttpMethod.POST,
                    new HttpEntity<>(request.toString(), headers),
                    JsonNode.class
                )
                .getStatusCode()
        );
    }

    @Test
    void confirmsMappingRevisionOnceAndRetainsTheOldSchema() {
        var f = fixture();
        var old = catalog.describe(f.actor().id(), f.source(), 1L);
        String fieldId = old.definition().at("/fields/1/id").textValue();
        var request = mapper
            .createObjectNode()
            .put("schemaRevision", 1)
            .put("fieldId", fieldId)
            .put("sample", "open");
        var conversion = request.putObject("conversion").put("method", "parse_boolean");
        conversion.putArray("trueTokens").add("open");
        conversion.putArray("falseTokens").add("closed");
        var headers = new HttpHeaders();
        headers.setBearerAuth(
            SignedUserTokens.create(f.actor().id(), f.actor().email(), f.actor().role())
        );
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String path = "/data/source-bindings/" + f.source();
        var preview = http.exchange(
            path + "/mapping-preview",
            HttpMethod.POST,
            new HttpEntity<>(request.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, preview.getStatusCode(), preview.getBody().toString());
        request.remove("sample");
        request.put("mappingDigest", preview.getBody().path("mappingDigest").textValue());
        var entity = new HttpEntity<>(request.toString(), headers);
        var receiptPath = path + "/mapping-receipts/" + request.path("mappingDigest").asText();
        var beforeConfirmation = http.exchange(
            receiptPath + "?schemaRevision=1",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertEquals(
            "MAPPING_RECEIPT_UNAVAILABLE",
            beforeConfirmation.getBody().path("code").asText()
        );
        assertEquals(1L, catalog.describe(f.actor().id(), f.source(), null).schemaRevision());
        var concurrent = CompletableFuture.supplyAsync(() ->
            http.exchange(path + "/mapping-confirmation", HttpMethod.POST, entity, JsonNode.class)
        );
        var applied = http.exchange(
            path + "/mapping-confirmation",
            HttpMethod.POST,
            entity,
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, applied.getStatusCode(), applied.getBody().toString());
        assertEquals(2, applied.getBody().path("schemaRevision").longValue());
        assertEquals(
            applied.getBody(),
            concurrent.orTimeout(10, TimeUnit.SECONDS).join().getBody()
        );
        assertEquals(f.device().toString(), applied.getBody().path("deviceId").textValue());
        assertEquals(
            applied.getBody(),
            http
                .exchange(path + "/mapping-confirmation", HttpMethod.POST, entity, JsonNode.class)
                .getBody()
        );
        var readback = http.exchange(
            receiptPath + "?schemaRevision=1",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, readback.getStatusCode());
        assertEquals(applied.getBody(), readback.getBody());
        var wrongBase = http.exchange(
            receiptPath + "?schemaRevision=2",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertEquals("MAPPING_RECEIPT_UNAVAILABLE", wrongBase.getBody().path("code").asText());
        assertEquals(
            old.definition(),
            catalog.describe(f.actor().id(), f.source(), 1L).definition()
        );
        assertEquals(
            "BOOLEAN",
            catalog
                .describe(f.actor().id(), f.source(), 2L)
                .definition()
                .at("/fields/1/type")
                .textValue()
        );
        var runtime = mapper.valueToTree(sources.findInternal(f.source()).mapping());
        assertEquals(2, runtime.at("/bindingSchemaPin/schemaRevision").longValue());
        assertEquals(
            "parse_boolean",
            runtime.path("reviewedBooleanMappings").elements().next().path("method").textValue()
        );
        conversion.putArray("trueTokens").add("wrong");
        assertEquals(
            HttpStatus.CONFLICT,
            http
                .exchange(
                    path + "/mapping-confirmation",
                    HttpMethod.POST,
                    new HttpEntity<>(request.toString(), headers),
                    JsonNode.class
                )
                .getStatusCode()
        );

        // Reconfigure the same raw input key, keeping the earlier conversion independently replayable.
        request.put("schemaRevision", 2).put("sample", "open");
        request.remove("mappingDigest");
        conversion.putArray("trueTokens").add("closed");
        conversion.putArray("falseTokens").add("open");
        var secondPreview = http.exchange(
            path + "/mapping-preview",
            HttpMethod.POST,
            new HttpEntity<>(request.toString(), headers),
            JsonNode.class
        );
        assertEquals(
            HttpStatus.OK,
            secondPreview.getStatusCode(),
            secondPreview.getBody().toString()
        );
        assertFalse(secondPreview.getBody().at("/literal/value").booleanValue());
        request.remove("sample");
        request.put("mappingDigest", secondPreview.getBody().path("mappingDigest").textValue());
        var second = http.exchange(
            path + "/mapping-confirmation",
            HttpMethod.POST,
            new HttpEntity<>(request.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody().toString());
        assertEquals(3, second.getBody().path("schemaRevision").longValue());
        assertEquals(
            applied.getBody(),
            http
                .exchange(path + "/mapping-confirmation", HttpMethod.POST, entity, JsonNode.class)
                .getBody()
        );
        var replay = mapper
            .createObjectNode()
            .put("schemaRevision", 2)
            .put("fieldId", fieldId)
            .put("sample", "open");
        var historical = http.exchange(
            path + "/mapping-replay",
            HttpMethod.POST,
            new HttpEntity<>(replay.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, historical.getStatusCode(), historical.getBody().toString());
        assertEquals(
            applied.getBody().path("schemaSnapshotId"),
            historical.getBody().path("schemaSnapshotId")
        );
        assertTrue(historical.getBody().at("/literal/value").booleanValue());
        replay.put("schemaRevision", 3);
        var latest = http.exchange(
            path + "/mapping-replay",
            HttpMethod.POST,
            new HttpEntity<>(replay.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, latest.getStatusCode(), latest.getBody().toString());
        assertFalse(latest.getBody().at("/literal/value").booleanValue());
        var rawEvent = mapper
            .createObjectNode()
            .put("schemaRevision", 2)
            .put("schemaSnapshotId", applied.getBody().path("schemaSnapshotId").textValue());
        var metrics = rawEvent.putObject("metrics");
        metrics.putObject("state").put("name", "open");
        metrics.putObject("switch").put("open", false);
        metrics.putObject("raw").put("voltage", 12);
        var oldEvent = http.exchange(
            path + "/mapping-event-replay",
            HttpMethod.POST,
            new HttpEntity<>(rawEvent.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, oldEvent.getStatusCode(), oldEvent.getBody().toString());
        assertTrue(
            oldEvent.getBody().at("/metrics/configuredFields/stateName/value").booleanValue()
        );
        assertEquals(
            12,
            oldEvent.getBody().at("/metrics/configuredFields/voltage/value").intValue()
        );
        assertEquals(f.device().toString(), oldEvent.getBody().path("deviceId").textValue());
        rawEvent
            .put("schemaRevision", 3)
            .put("schemaSnapshotId", second.getBody().path("schemaSnapshotId").textValue());
        var newEvent = http.exchange(
            path + "/mapping-event-replay",
            HttpMethod.POST,
            new HttpEntity<>(rawEvent.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.OK, newEvent.getStatusCode(), newEvent.getBody().toString());
        assertFalse(
            newEvent.getBody().at("/metrics/configuredFields/stateName/value").booleanValue()
        );
        jdbc.update(
            "UPDATE cfg_source_binding_snapshot SET canonical_normalization='{}' WHERE source_id=? AND schema_revision=2",
            f.source()
        );
        rawEvent
            .put("schemaRevision", 2)
            .put("schemaSnapshotId", applied.getBody().path("schemaSnapshotId").textValue());
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            http
                .exchange(
                    path + "/mapping-event-replay",
                    HttpMethod.POST,
                    new HttpEntity<>(rawEvent.toString(), headers),
                    JsonNode.class
                )
                .getStatusCode()
        );
        jdbc.update(
            """
            UPDATE cfg_source_mapping_receipt SET canonical_conversion=? WHERE source_id=? AND applied_revision=2
            """,
            VclCanonical.encode(conversion),
            f.source()
        );
        replay.put("schemaRevision", 2);
        var corrupt = http.exchange(
            path + "/mapping-replay",
            HttpMethod.POST,
            new HttpEntity<>(replay.toString(), headers),
            JsonNode.class
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, corrupt.getStatusCode());
        assertEquals("MAPPING_RECEIPT_INTEGRITY", corrupt.getBody().path("code").textValue());
    }

    @Test
    void sourceWritesPublishTypedImmutableMetadataEvenWhenKafkaIsDisabled() throws Exception {
        Fixture fixture = fixture();
        var value = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertEquals(1, value.schemaRevision());
        assertEquals(fixture.source(), value.id());
        assertEquals(fixture.actor().id(), value.actorId());
        assertEquals(
            accounts.findByUserId(fixture.actor().id()).orElseThrow().getId(),
            value.accountId()
        );
        assertEquals(2, value.definition().path("fields").size());
        assertEquals("BOOLEAN", value.definition().at("/fields/0/type").asText());
        assertEquals("TEXT", value.definition().at("/fields/1/type").asText());
        assertEquals(1, value.definition().path("unsupportedFields").size());
        assertEquals(
            "EXACT_NUMERIC_CONVERSION_REQUIRED",
            value.definition().at("/unsupportedFields/0/reason").asText()
        );
        assertEquals(
            VclCanonical.sha256(VclCanonical.encode(value.definition())),
            value.definitionDigest()
        );
        assertTrue(value.sourceActive());
        assertFalse(value.ruleExecutionReady());
        assertEquals("UNKNOWN", value.captureCapabilities().path("transitionCoverage").asText());
        assertFalse(value.captureCapabilities().path("negativeEvidenceReady").asBoolean());
        assertTrue(value.captureCapabilities().path("inputGeneration").isNull());
        assertFalse(value.definition().toString().contains("private-adapter-host"));
        assertFalse(value.toString().contains("private"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode changed = mapper.valueToTree(request(fixture.external(), "switch.open", true));
        (
            (com.fasterxml.jackson.databind.node.ObjectNode) changed.at(
                "/mapping/telemetryPolicy/fields/2"
            )
        ).put("unit", "C");
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            mapper.treeToValue(changed, DataSourceRequest.class)
        );
        var unsupportedUnit = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertEquals(
            "UNIT_MAPPING_REQUIRED",
            unsupportedUnit.definition().at("/unsupportedFields/0/reason").asText()
        );
        assertEquals(2, unsupportedUnit.definition().path("fields").size());
    }

    @Test
    void fieldIdentitiesAndOldRevisionsSurviveEditsRemovalAndReaddition() {
        Fixture fixture = fixture();
        var first = catalog.describe(fixture.actor().id(), fixture.source(), null);
        String doorId = first.definition().at("/fieldMappings/0/fieldId").asText();
        String textId = first.definition().at("/fieldMappings/1/fieldId").asText();
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            request(fixture.external(), "switch.changed", false)
        );
        var second = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertEquals(2, second.schemaRevision());
        assertEquals(doorId, second.definition().at("/fieldMappings/0/fieldId").asText());
        assertEquals("switch.changed", second.definition().at("/fieldMappings/0/path").asText());
        assertFalse(second.definition().path("fieldMappings").toString().contains("stateName"));
        var historical = catalog.describe(fixture.actor().id(), fixture.source(), 1L);
        assertEquals(2, historical.currentSchemaRevision());
        assertEquals(first.definitionDigest(), historical.definitionDigest());
        assertEquals(first.snapshotId(), historical.snapshotId());
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            request(fixture.external(), "switch.changed", true)
        );
        var third = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertEquals(3, third.schemaRevision());
        assertEquals(doorId, third.definition().at("/fieldMappings/0/fieldId").asText());
        assertEquals(textId, third.definition().at("/fieldMappings/1/fieldId").asText());
        assertEquals(3, count(fixture.source()));
    }

    @Test
    void configurationReplayAndTelemetryLastSeenDoNotMintSchemaRevisions() {
        Fixture fixture = fixture();
        var first = catalog.describe(fixture.actor().id(), fixture.source(), null);
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            request(fixture.external(), "switch.open", true)
        );
        sources.markTokenUsed(fixture.source());
        var second = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertEquals(first, second);
        assertEquals(1, count(fixture.source()));
    }

    @Test
    void journalJoinsSourceTransactionAndRollsBackWithIt() {
        Fixture fixture = fixture();
        var before = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertThrows(IllegalStateException.class, () ->
            new TransactionTemplate(transactions).execute(status -> {
                sources.update(
                    fixture.actor(),
                    fixture.device(),
                    fixture.source(),
                    request(fixture.external(), "different.path", true)
                );
                throw new IllegalStateException("injected rollback");
            })
        );
        assertEquals(before, catalog.describe(fixture.actor().id(), fixture.source(), null));
        assertEquals(1, count(fixture.source()));
    }

    @Test
    void concurrentSourceUpdatesHaveMonotoneDistinctSnapshots() throws Exception {
        Fixture fixture = fixture();
        var a = CompletableFuture.runAsync(() ->
            sources.update(
                fixture.actor(),
                fixture.device(),
                fixture.source(),
                request(fixture.external(), "switch.first", true)
            )
        );
        var b = CompletableFuture.runAsync(() ->
            sources.update(
                fixture.actor(),
                fixture.device(),
                fixture.source(),
                request(fixture.external(), "switch.second", true)
            )
        );
        CompletableFuture.allOf(a, b).get(15, TimeUnit.SECONDS);
        assertEquals(3, count(fixture.source()));
        assertEquals(
            3,
            catalog.describe(fixture.actor().id(), fixture.source(), null).schemaRevision()
        );
        assertEquals(
            3,
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT snapshot_id) FROM cfg_source_binding_snapshot WHERE source_id=?",
                Integer.class,
                fixture.source()
            )
        );
    }

    @Test
    void signedHttpRejectsSpoofingForeignResourcesAndAccountRevocation() {
        Fixture fixture = fixture();
        String path = "/data/source-bindings/" + fixture.source();
        var spoofed = new HttpHeaders();
        spoofed.set("X-User-Id", fixture.actor().id().toString());
        spoofed.set("X-Internal-Service-Token", "dev-internal-token");
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            http
                .exchange(path, HttpMethod.GET, new HttpEntity<>(spoofed), JsonNode.class)
                .getStatusCode()
        );
        var valid = get(fixture.actor(), path);
        assertEquals(HttpStatus.OK, valid.getStatusCode());
        AuthenticatedUser other = actor();
        accounts.currentAccount(other);
        assertEquals(HttpStatus.NOT_FOUND, get(other, path).getStatusCode());
        assertEquals(
            HttpStatus.NOT_FOUND,
            get(other, "/data/source-bindings/" + UUID.randomUUID()).getStatusCode()
        );
        assertEquals(
            HttpStatus.BAD_REQUEST,
            get(fixture.actor(), path + "?schemaRevision=0").getStatusCode()
        );
        assertEquals(
            HttpStatus.NOT_FOUND,
            get(fixture.actor(), path + "?schemaRevision=200").getStatusCode()
        );
        accounts.syncUser(fixture.actor().id(), fixture.actor().email(), AccountStatus.DISABLED);
        assertEquals(HttpStatus.FORBIDDEN, get(fixture.actor(), path).getStatusCode());
    }

    @Test
    void readsNeverProvisionMissingAccountsOrRepairMissingJournals() {
        AuthenticatedUser unknown = actor();
        assertEquals(
            HttpStatus.FORBIDDEN,
            get(unknown, "/data/source-bindings/" + UUID.randomUUID()).getStatusCode()
        );
        assertTrue(accounts.findByUserId(unknown.id()).isEmpty());
        Fixture fixture = fixture();
        jdbc.update("DELETE FROM cfg_source_binding_snapshot WHERE source_id=?", fixture.source());
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            get(fixture.actor(), "/data/source-bindings/" + fixture.source()).getStatusCode()
        );
        assertEquals(0, count(fixture.source()));
    }

    @Test
    void sourceDisableAndDeviceDeletionAreFreshOwnerFences() {
        Fixture fixture = fixture();
        var before = catalog.describe(fixture.actor().id(), fixture.source(), null);
        sources.disable(fixture.actor(), fixture.device(), fixture.source());
        var after = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertFalse(after.sourceActive());
        assertNotEquals(before.sourceStateDigest(), after.sourceStateDigest());
        jdbc.update("UPDATE devices SET status='DECOMMISSIONED' WHERE id=?", fixture.device());
        assertEquals(
            "SOURCE_BINDING_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                catalog.describe(fixture.actor().id(), fixture.source(), null)
            ).code()
        );
    }

    @Test
    void corruptImmutableSnapshotFailsInsteadOfReconstructingFromLatest() {
        Fixture fixture = fixture();
        jdbc.update(
            "UPDATE cfg_source_binding_snapshot SET canonical_definition='{}' WHERE source_id=?",
            fixture.source()
        );
        var response = get(fixture.actor(), "/data/source-bindings/" + fixture.source());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("BINDING_SNAPSHOT_INTEGRITY", response.getBody().path("code").asText());
    }

    private Fixture fixture() {
        AuthenticatedUser actor = actor();
        UUID device = devices
            .create(
                actor,
                new DeviceRequest(null, "Binding fixture", "FRIDGE", null, null, Map.of())
            )
            .id();
        String external = UUID.randomUUID().toString();
        UUID source = sources.create(actor, device, request(external, "switch.open", true)).id();
        return new Fixture(actor, device, source, external);
    }

    @Test
    void signedCaptureDescriptionBindsRealSourceSchemaToClosedJournalMetadata() {
        var fixture = fixture();
        var result = get(fixture.actor(), capturePath(fixture));
        assertEquals(HttpStatus.OK, result.getStatusCode(), result.getBody().toString());
        var decoded = com.voltcanary.vcl.VclSourceCaptureDescription.parse(
            result.getBody().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        assertEquals(fixture.source(), decoded.source().id());
        assertEquals(fixture.actor().id(), decoded.source().actorId());
        assertTrue(decoded.capture().journalReady());
        assertFalse(decoded.capture().ruleExecutionReady());
        assertFalse(decoded.capture().negativeEvidenceReady());
        assertEquals("UNKNOWN", decoded.capture().transitionCoverage());
        assertNotEquals(decoded.source().snapshotId(), decoded.capture().inputGeneration());
    }

    @Test
    void captureUnavailableStateIsTruthfulAndInactiveSourceDoesNotCallJournalOwner() {
        var fixture = fixture();
        CaptureJournalFixture.state = "SCHEMA_PENDING";
        var response = get(fixture.actor(), capturePath(fixture));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().at("/capture/journalReady").asBoolean());
        assertTrue(response.getBody().at("/capture/inputGeneration").isNull());
        sources.disable(fixture.actor(), fixture.device(), fixture.source());
        int before = CaptureJournalFixture.READS.get();
        var disabled = get(fixture.actor(), capturePath(fixture));
        assertEquals(HttpStatus.OK, disabled.getStatusCode());
        assertEquals("DISABLED", disabled.getBody().at("/capture/state").asText());
        assertEquals(before, CaptureJournalFixture.READS.get());
    }

    @Test
    void foreignAndStaleSchemaCaptureRequestsFailBeforeRemoteOwnerIo() {
        var fixture = fixture();
        var foreign = actor();
        int before = CaptureJournalFixture.READS.get();
        assertEquals(HttpStatus.FORBIDDEN, get(foreign, capturePath(fixture)).getStatusCode());
        assertEquals(before, CaptureJournalFixture.READS.get());
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            request(fixture.external(), "new.path", true)
        );
        assertEquals(
            HttpStatus.CONFLICT,
            get(fixture.actor(), capturePath(fixture)).getStatusCode()
        );
        assertEquals(before, CaptureJournalFixture.READS.get());
    }

    @Test
    void sourceAndAccountRevocationDuringCaptureRpcCannotReturnUsableJournal() {
        var sourceFixture = fixture();
        CaptureJournalFixture.AFTER_READ.set(() ->
            sources.disable(sourceFixture.actor(), sourceFixture.device(), sourceFixture.source())
        );
        assertEquals(
            HttpStatus.CONFLICT,
            get(sourceFixture.actor(), capturePath(sourceFixture)).getStatusCode()
        );
        var accountFixture = fixture();
        CaptureJournalFixture.AFTER_READ.set(() ->
            accounts.syncUser(
                accountFixture.actor().id(),
                accountFixture.actor().email(),
                AccountStatus.DISABLED
            )
        );
        assertEquals(
            HttpStatus.FORBIDDEN,
            get(accountFixture.actor(), capturePath(accountFixture)).getStatusCode()
        );
    }

    @Test
    void captureOwnerFailuresRedirectsAndFabricatedReadinessAreRejectedWithoutBodies() {
        var fixture = fixture();
        for (int status : List.of(302, 401, 404, 500, 503)) {
            CaptureJournalFixture.status = status;
            int before = CaptureJournalFixture.READS.get();
            var response = get(fixture.actor(), capturePath(fixture));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertFalse(response.getBody().toString().contains("private capture owner detail"));
            assertEquals(before + 1, CaptureJournalFixture.READS.get());
        }
        CaptureJournalFixture.status = 200;
        for (java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> change : List.<
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>
        >of(
            value -> value.put("negativeEvidenceReady", true),
            value -> value.put("ruleExecutionReady", true),
            value -> value.put("transitionCoverage", "COMPLETE"),
            value -> value.put("maximumGapUs", 1000),
            value ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("pin")).put(
                    "accountId",
                    UUID.randomUUID().toString()
                ),
            value -> value.put("inputGeneration", value.at("/pin/snapshotId").asText())
        )) {
            CaptureJournalFixture.mutate = change;
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                get(fixture.actor(), capturePath(fixture)).getStatusCode()
            );
        }
    }

    private String capturePath(Fixture fixture) {
        return "/data/source-bindings/" + fixture.source() + "/capture?schemaRevision=1";
    }

    @Test
    void signedObservationReadsUseTheRealSourcePinAndTheClosedJournalCodec() throws Exception {
        var fixture = fixture();
        var request = observationRequest(fixture);
        configureObservationPage(fixture);
        var response = observations(fixture.actor(), fixture.source(), request.bytes());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var page = com.voltcanary.vcl.VclObservationPage.parse(
            mapper.writeValueAsBytes(response.getBody())
        );
        assertEquals(request, page.request());
        assertEquals(1, page.observations().size());
        assertFalse(page.ruleExecutionReady());
        assertEquals("UNKNOWN", page.upstreamCoverage());
        assertTrue(page.observations().getFirst().toString().contains("private-observation"));
    }

    @Test
    void foreignAccountWrongPathOrForgedSchemaPinCannotReachJournalReads() throws Exception {
        var fixture = fixture();
        var request = observationRequest(fixture);
        configureObservationPage(fixture);
        var foreign = fixture();
        int before = CaptureJournalFixture.READS.get();
        assertEquals(
            HttpStatus.NOT_FOUND,
            observations(foreign.actor(), fixture.source(), request.bytes()).getStatusCode()
        );
        assertEquals(
            HttpStatus.BAD_REQUEST,
            observations(fixture.actor(), UUID.randomUUID(), request.bytes()).getStatusCode()
        );
        var value = mapper.readTree(request.bytes());
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("pin")).put(
            "definitionDigest",
            "b".repeat(64)
        );
        assertEquals(
            HttpStatus.CONFLICT,
            observations(
                fixture.actor(),
                fixture.source(),
                mapper.writeValueAsBytes(value)
            ).getStatusCode()
        );
        assertEquals(before, CaptureJournalFixture.READS.get());
    }

    @Test
    void sourceOrAccountRevocationDuringObservationRpcCannotDiscloseThePage() throws Exception {
        var sourceFixture = fixture();
        configureObservationPage(sourceFixture);
        var sourceRequest = observationRequest(sourceFixture);
        CaptureJournalFixture.AFTER_READ.set(() ->
            sources.disable(sourceFixture.actor(), sourceFixture.device(), sourceFixture.source())
        );
        var sourceResponse = observations(
            sourceFixture.actor(),
            sourceFixture.source(),
            sourceRequest.bytes()
        );
        assertEquals(HttpStatus.CONFLICT, sourceResponse.getStatusCode());
        assertFalse(sourceResponse.getBody().toString().contains("private-observation"));
        var accountFixture = fixture();
        configureObservationPage(accountFixture);
        var accountRequest = observationRequest(accountFixture);
        CaptureJournalFixture.AFTER_READ.set(() ->
            accounts.syncUser(
                accountFixture.actor().id(),
                accountFixture.actor().email(),
                AccountStatus.DISABLED
            )
        );
        var accountResponse = observations(
            accountFixture.actor(),
            accountFixture.source(),
            accountRequest.bytes()
        );
        assertEquals(HttpStatus.FORBIDDEN, accountResponse.getStatusCode());
        assertFalse(accountResponse.getBody().toString().contains("private-observation"));
    }

    @Test
    void journalFailureOrExpandedReadinessCannotReturnAnEmptyUsablePage() throws Exception {
        var fixture = fixture();
        var request = observationRequest(fixture);
        configureObservationPage(fixture);
        for (int status : List.of(302, 401, 404, 409, 422, 503)) {
            CaptureJournalFixture.status = status;
            var response = observations(fixture.actor(), fixture.source(), request.bytes());
            assertEquals(
                status == 409
                    ? HttpStatus.CONFLICT
                    : status == 422
                      ? HttpStatus.UNPROCESSABLE_CONTENT
                      : HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode()
            );
            assertFalse(response.getBody().toString().contains("private capture owner detail"));
        }
        CaptureJournalFixture.status = 200;
        CaptureJournalFixture.mutate = value -> value.put("ruleExecutionReady", true);
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            observations(fixture.actor(), fixture.source(), request.bytes()).getStatusCode()
        );
    }

    @Test
    void malformedObservationEnvelopesFailBeforeSourceJournalIo() throws Exception {
        var fixture = fixture();
        var value = mapper.readTree(observationRequest(fixture).bytes());
        ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("approved", true);
        int before = CaptureJournalFixture.READS.get();
        assertEquals(
            HttpStatus.BAD_REQUEST,
            observations(
                fixture.actor(),
                fixture.source(),
                mapper.writeValueAsBytes(value)
            ).getStatusCode()
        );
        assertEquals(before, CaptureJournalFixture.READS.get());
    }

    private com.voltcanary.vcl.VclObservationRead observationRequest(Fixture fixture) {
        var description = catalog.describe(fixture.actor().id(), fixture.source(), 1L);
        var pin = catalog
            .publication(description.accountId(), fixture.source())
            .orElseThrow()
            .pin();
        var actual = new com.voltcanary.vcl.VclObservationReadiness.Pin(
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId(),
            pin.externalSourceId(),
            pin.schemaRevision(),
            pin.snapshotId(),
            pin.definitionDigest()
        );
        return new com.voltcanary.vcl.VclObservationRead(actual, UUID.randomUUID(), "0", "1", 1);
    }

    private void configureObservationPage(Fixture fixture) {
        var definition = catalog.describe(fixture.actor().id(), fixture.source(), 1L).definition();
        CaptureJournalFixture.page = request -> {
            var row = mapper
                .createObjectNode()
                .put("profile", com.voltcanary.vcl.VclObservationProfile.VERSION)
                .put("inputEventId", UUID.randomUUID().toString())
                .put("inputGeneration", request.inputGeneration().toString())
                .put("eventTime", "2026-11-05T00:00:00Z")
                .put("receivedAt", "2026-11-05T00:00:01Z")
                .put("recordedAt", "2026-11-05T00:00:02Z")
                .put("checkpoint", "1")
                .put("upstreamCoverage", "UNKNOWN")
                .put("eventTimeProvenance", "UNQUALIFIED")
                .put("negativeEvidenceReady", false)
                .put("ruleExecutionReady", false);
            row.set("bindingSchemaPin", mapper.valueToTree(request.pin()));
            var fields = row.putArray("fields");
            for (var field : definition.path("fields")) {
                var value = fields
                    .addObject()
                    .put("fieldId", field.path("id").asText())
                    .put("type", field.path("type").asText())
                    .put("knowledge", "KNOWN")
                    .putNull("reason");
                if (field.path("type").asText().equals("BOOLEAN")) value.put("value", false);
                else value.put("value", "private-observation");
            }
            return com.voltcanary.vcl.VclObservationPage.of(request, List.of(row), null);
        };
    }

    private org.springframework.http.ResponseEntity<JsonNode> observations(
        AuthenticatedUser actor,
        UUID source,
        byte[] body
    ) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(SignedUserTokens.create(actor.id(), actor.email(), actor.role()));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", UUID.randomUUID().toString());
        return http.exchange(
            "/data/source-bindings/" + source + "/observations",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class
        );
    }

    @Test
    void runtimePublicationUsesSanitizedRealJournalAndDiscardsAuthoredPins() {
        Fixture fixture = fixture();
        var original = request(fixture.external(), "switch.open", true);
        var mapping = new java.util.HashMap<String, Object>(original.mapping());
        mapping.put(
            "bindingSchemaPin",
            Map.of("bindingId", UUID.randomUUID().toString(), "ruleExecutionReady", true)
        );
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            new DataSourceRequest(
                original.sourceType(),
                original.protocol(),
                original.externalId(),
                mapping,
                original.adapterConfig(),
                original.credentialReferences(),
                original.homeRegion()
            )
        );
        var description = catalog.describe(fixture.actor().id(), fixture.source(), null);
        var publication = catalog
            .publication(description.accountId(), fixture.source())
            .orElseThrow();
        assertEquals(description.definitionDigest(), publication.pin().definitionDigest());
        assertEquals(description.snapshotId(), publication.pin().snapshotId());
        assertEquals(description.definition(), json(publication.canonicalDefinition()));
        assertFalse(publication.canonicalDefinition().contains("private-adapter-host"));
        assertFalse(publication.canonicalDefinition().contains("private-reference"));
        var internal = sources.findInternal(fixture.source());
        var pin = mapper.convertValue(
            internal.mapping().get("bindingSchemaPin"),
            com.voltcanary.common.events.telemetry.SourceBindingSchemaPin.class
        );
        assertEquals(publication.pin(), pin);
        assertEquals(2, pin.schemaRevision());
        assertFalse(
            internal.mapping().get("bindingSchemaPin").toString().contains("ruleExecutionReady")
        );
    }

    @Test
    void schemaAndRuntimeEventsCommitTogetherAndRollbackLeavesNoPublication() {
        Fixture fixture = fixture();
        var account = accounts.findByUserId(fixture.actor().id()).orElseThrow().getId();
        var publisher = new com.voltcanary.device.event.SourceRuntimeConfigEventPublisher(
            outbox,
            new com.voltcanary.device.config.DeviceKafkaProperties(),
            mapper,
            jdbc,
            catalog
        );
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(ignored ->
            publisher.publish(account, sourceRows.findById(fixture.source()).orElseThrow())
        );
        String schemaTopic =
            com.voltcanary.device.event.SourceRuntimeConfigEventPublisher.BINDING_SCHEMA_TOPIC;
        String schemaKey = fixture.source() + ":1";
        var schema = jdbc.queryForObject(
            "SELECT payload_json FROM device_event_outbox WHERE message_key=? AND topic=?",
            String.class,
            schemaKey,
            schemaTopic
        );
        var decoded = readSchema(schema);
        assertEquals(
            catalog.publication(account, fixture.source()).orElseThrow().pin(),
            decoded.pin()
        );
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_event_outbox WHERE message_key IN (?,?)",
                Integer.class,
                fixture.source().toString(),
                schemaKey
            )
        );
        assertThrows(IllegalStateException.class, () ->
            transaction.executeWithoutResult(ignored -> {
                publisher.publish(account, sourceRows.findById(fixture.source()).orElseThrow());
                throw new IllegalStateException("rollback fixture");
            })
        );
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_event_outbox WHERE message_key IN (?,?)",
                Integer.class,
                fixture.source().toString(),
                schemaKey
            )
        );
        sources.disable(fixture.actor(), fixture.device(), fixture.source());
        assertFalse(catalog.publication(account, fixture.source()).orElseThrow().sourceActive());
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private com.voltcanary.common.events.device.SourceBindingSchemaEvent readSchema(String value) {
        try {
            var wire = mapper.readTree(value);
            assertEquals("SourceBindingSchemaPublished", wire.path("eventType").asText());
            assertEquals(1, wire.path("eventVersion").asInt());
            var payload = mapper.treeToValue(
                wire.path("payload"),
                com.voltcanary.common.events.device.SourceBindingSchemaEvent.Payload.class
            );
            assertEquals(payload.pin().accountId().toString(), wire.path("accountId").asText());
            return new com.voltcanary.common.events.device.SourceBindingSchemaEvent(
                UUID.fromString(wire.path("eventId").asText()),
                "v1",
                payload.pin(),
                payload.canonicalDefinition(),
                payload.sourceActive(),
                java.time.Instant.parse(wire.path("occurredAt").asText()),
                wire.path("producer").asText()
            );
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void adjacentLargeLegacyAdapterIntegersCannotAliasTheConfigurationFingerprint() {
        Fixture fixture = fixture();
        var original = request(fixture.external(), "switch.open", true);
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            new DataSourceRequest(
                original.sourceType(),
                original.protocol(),
                original.externalId(),
                original.mapping(),
                Map.of("counter", 9007199254740992L),
                original.credentialReferences(),
                original.homeRegion()
            )
        );
        var first = catalog.describe(fixture.actor().id(), fixture.source(), null);
        sources.update(
            fixture.actor(),
            fixture.device(),
            fixture.source(),
            new DataSourceRequest(
                original.sourceType(),
                original.protocol(),
                original.externalId(),
                original.mapping(),
                Map.of("counter", 9007199254740993L),
                original.credentialReferences(),
                original.homeRegion()
            )
        );
        var second = catalog.describe(fixture.actor().id(), fixture.source(), null);
        assertEquals(first.schemaRevision() + 1, second.schemaRevision());
        assertNotEquals(
            first.definition().path("configurationDigest"),
            second.definition().path("configurationDigest")
        );
        assertNotEquals(first.definitionDigest(), second.definitionDigest());
    }

    private AuthenticatedUser actor() {
        return new AuthenticatedUser(UUID.randomUUID(), "source-binding@example.invalid", "USER");
    }

    private DataSourceRequest request(String external, String path, boolean storeText) {
        var door = Map.of(
            "key",
            "door",
            "path",
            path,
            "label",
            "Door",
            "type",
            "boolean",
            "unit",
            "boolean",
            "store",
            true,
            "display",
            true
        );
        var text = Map.of(
            "key",
            "stateName",
            "path",
            "state.name",
            "label",
            "State",
            "type",
            "string",
            "unit",
            "",
            "store",
            storeText,
            "display",
            true
        );
        var numeric = Map.of(
            "key",
            "voltage",
            "path",
            "raw.voltage",
            "label",
            "Voltage",
            "type",
            "number",
            "unit",
            "V",
            "store",
            true,
            "display",
            true
        );
        return new DataSourceRequest(
            DataSourceType.SENSOR,
            DataSourceProtocol.REST,
            external,
            Map.of("telemetryPolicy", Map.of("fields", List.of(door, text, numeric))),
            Map.of("host", "private-adapter-host"),
            Map.of("auth", "private-reference"),
            "default"
        );
    }

    private int count(UUID source) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM cfg_source_binding_snapshot WHERE source_id=?",
            Integer.class,
            source
        );
    }

    private org.springframework.http.ResponseEntity<JsonNode> get(
        AuthenticatedUser actor,
        String path
    ) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(SignedUserTokens.create(actor.id(), actor.email(), actor.role()));
        headers.set("X-User-Id", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
}
