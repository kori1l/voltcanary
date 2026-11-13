package com.voltcanary.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.test.SignedUserTokens;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.port.NativeCollectionReviewStore;
import com.voltcanary.device.configurable.port.NativeDatasetCatalog;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.vcl.VclException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:native-owner-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true", "app.kafka.publish-enabled=false", "app.kafka.consume-enabled=false"
})
class NativeCollectionsApiTest extends SignedUserTest {
    @Autowired TestRestTemplate rest;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired NativeDatasetCatalog catalog;
    @MockitoSpyBean NativeCollectionReviewStore reviews;
    @MockitoSpyBean com.voltcanary.device.configurable.port.NativeRowMutations rowMutations;

    @Test
    void reviewedSystemKeyRowsAreQueryableAndReplayKeepsTheSameReceipt() {
        var actor = actor();
        var dataset = createDataset(actor);
        String key = UUID.randomUUID().toString();
        var reserved = rowReservation(actor, dataset, key);
        var prepared = rowPreparation(actor, dataset, reserved, key);
        String path = "/data/reviews/" + reserved.path("reviewId").asText();
        assertEquals(HttpStatus.NOT_FOUND, call(actor(), HttpMethod.GET, path, null).getStatusCode());
        var applied = call(actor, HttpMethod.POST, path + "/apply", application(prepared, key));
        assertEquals(HttpStatus.OK, applied.getStatusCode(), applied.getBody().toString());
        assertEquals("1", applied.getBody().at("/result/recordVersion").asText());
        assertEquals(reserved.path("createdRecordId"), applied.getBody().at("/result/recordId"));
        assertEquals(applied.getBody(), call(actor, HttpMethod.POST, path + "/apply", application(prepared, key)).getBody());
        assertEquals(applied.getBody(), call(actor, HttpMethod.POST, path + "/reconcile", "{}").getBody());
        var request = mapper.createObjectNode();
        var query = request.putObject("document").put("language", "VCL/1").put("kind", "QUERY").putObject("spec");
        query.putObject("target").put("datasetId", dataset.path("resourceId").asText()).put("schemaRevision", 1);
        query.putArray("select").add(dataset.at("/typedDiff/fields/0/id").asText());
        query.putObject("where").put("op", "CONST").putObject("literal").put("type", "BOOLEAN").put("value", true);
        var description = call(actor, HttpMethod.GET, "/data/datasets/" + dataset.path("resourceId").asText(), null).getBody();
        query.putArray("order").addObject().put("fieldId", description.at("/definition/systemFields/recordIdFieldId").asText()).put("direction", "ASC");
        query.put("limit", 10).putNull("cursor").putObject("read").put("mode", "LIVE");
        var page = call(actor, HttpMethod.POST, "/data/datasets/" + dataset.path("resourceId").asText() + "/query", request.toString());
        assertEquals(HttpStatus.OK, page.getStatusCode(), page.getBody().toString());
        assertEquals("COMPLETE", page.getBody().path("completeness").asText());
        assertEquals(1, page.getBody().path("rows").size());
        assertEquals(reserved.path("createdRecordId"), page.getBody().at("/rows/0/recordId"));
        String datasetPath = "/data/datasets/" + dataset.path("resourceId").asText();
        var sealed = call(actor, HttpMethod.POST, datasetPath + "/query-snapshots", request.toString());
        assertEquals(HttpStatus.OK, sealed.getStatusCode(), sealed.getBody().toString());
        assertEquals(1, sealed.getBody().path("rowCount").intValue());
        query.putObject("read").put("mode", "SNAPSHOT").put("snapshotId", sealed.getBody().path("snapshotId").textValue());
        var snapshot = call(actor, HttpMethod.POST, datasetPath + "/query", request.toString());
        assertEquals(HttpStatus.OK, snapshot.getStatusCode(), snapshot.getBody().toString());
        assertEquals(page.getBody().path("rows"), snapshot.getBody().path("rows"));
        assertEquals("SNAPSHOT", snapshot.getBody().at("/readManifest/consistency").textValue());
        assertEquals(sealed.getBody().path("snapshotId"), snapshot.getBody().at("/readManifest/snapshotId"));
        assertEquals(HttpStatus.NOT_FOUND, call(actor(), HttpMethod.POST, datasetPath + "/query", request.toString()).getStatusCode());
    }

    @Test
    void lostRowCommitAcknowledgementRecoversWithoutAnotherDispatch() {
        var actor = actor();
        var dataset = createDataset(actor);
        String key = UUID.randomUUID().toString();
        var prepared = rowPreparation(actor, dataset, rowReservation(actor, dataset, key), key);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new VclException("ROW_COMMIT_UNCERTAIN", "");
        }).when(rowMutations).apply(any(UUID.class), any(UUID.class), any(UUID.class));
        String path = "/data/reviews/" + prepared.path("reviewId").asText();
        var recovered = call(actor, HttpMethod.POST, path + "/apply", application(prepared, key));
        assertEquals(HttpStatus.OK, recovered.getStatusCode(), recovered.getBody().toString());
        assertEquals("SUCCEEDED", recovered.getBody().path("status").asText());
        var reconciled = call(actor, HttpMethod.POST, path + "/reconcile", "{}");
        assertEquals(recovered.getBody(), reconciled.getBody());
        org.mockito.Mockito.verify(rowMutations, org.mockito.Mockito.times(1)).apply(any(UUID.class), any(UUID.class), any(UUID.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_row_receipt WHERE review_id=?", Integer.class, UUID.fromString(prepared.path("reviewId").asText())));
        assertEquals(2L, jdbc.queryForObject("SELECT data_version FROM cfg_dataset WHERE id=?", Long.class, UUID.fromString(dataset.path("resourceId").asText())));
    }

    @Test
    void rowUnknownMarkerDoesNotRedispatchOnReconcileAndStrictBodiesStayClosed() {
        var actor = actor();
        var dataset = createDataset(actor);
        String key = UUID.randomUUID().toString();
        var prepared = rowPreparation(actor, dataset, rowReservation(actor, dataset, key), key);
        doThrow(new VclException("ROW_COMMIT_UNCERTAIN", "")).when(rowMutations).apply(any(UUID.class), any(UUID.class), any(UUID.class));
        String path = "/data/reviews/" + prepared.path("reviewId").asText();
        assertEquals(HttpStatus.ACCEPTED, call(actor, HttpMethod.POST, path + "/apply", application(prepared, key)).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, call(actor, HttpMethod.POST, path + "/reconcile", "{}").getStatusCode());
        org.mockito.Mockito.verify(rowMutations, org.mockito.Mockito.times(1)).apply(any(UUID.class), any(UUID.class), any(UUID.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_row_receipt WHERE review_id=?", Integer.class, UUID.fromString(prepared.path("reviewId").asText())));
        assertEquals(HttpStatus.BAD_REQUEST, call(actor, HttpMethod.POST, path + "/reconcile", "{\"approved\":true}").getStatusCode());
    }

    private JsonNode createDataset(AuthenticatedUser actor) {
        var prepared = prepare(actor, reserve(actor), "Rows");
        assertEquals(HttpStatus.OK, call(actor, HttpMethod.POST, "/data/reviews/" + prepared.path("reviewId").asText() + "/apply", application(prepared, UUID.randomUUID().toString())).getStatusCode());
        return prepared;
    }

    private JsonNode rowReservation(AuthenticatedUser actor, JsonNode dataset, String key) {
        var body = mapper.createObjectNode().put("kind", "MUTATION").put("idempotencyKey", key);
        body.putObject("target").put("datasetId", dataset.path("resourceId").asText()).put("schemaRevision", 1);
        var result = call(actor, HttpMethod.POST, "/data/review-reservations", body.toString());
        assertEquals(HttpStatus.OK, result.getStatusCode(), result.getBody().toString());
        return result.getBody();
    }

    private JsonNode rowPreparation(AuthenticatedUser actor, JsonNode dataset, JsonNode reservation, String key) {
        var description = call(actor, HttpMethod.GET, "/data/datasets/" + dataset.path("resourceId").asText(), null).getBody();
        var body = mapper.createObjectNode().put("reservationId", reservation.path("reviewId").asText());
        var spec = body.putObject("document").put("language", "VCL/1").put("kind", "MUTATION").putObject("spec");
        spec.put("operation", "INSERT").put("mutationId", reservation.path("mutationId").asText()).put("reviewId", reservation.path("reviewId").asText())
                .put("idempotencyKey", key).putNull("expectedRowVersion");
        spec.putObject("target").put("datasetId", dataset.path("resourceId").asText()).put("schemaRevision", 1);
        spec.putObject("targetPrecondition").put("mode", "NATIVE").put("generation", description.path("generation").asText()).put("dataVersion", description.path("dataVersion").asLong());
        spec.putArray("key");
        spec.putArray("values").addObject().put("fieldId", dataset.at("/typedDiff/fields/0/id").asText()).putObject("literal").put("type", "TEXT").put("value", "reviewed row");
        var response = call(actor, HttpMethod.POST, "/data/datasets/" + dataset.path("resourceId").asText() + "/mutation-reviews", body.toString());
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody().toString());
        return response.getBody();
    }

    @Test
    void signedOwnerReservesPreparesAppliesAndReadsExactlyTheCreatedSchema() {
        var actor = actor();
        var reservation = reserve(actor);
        var prepared = prepare(actor, reservation, "Customer table");
        assertEquals("PREPARED", prepared.path("status").asText());
        String url = "/data/reviews/" + prepared.path("reviewId").asText() + "/apply";
        var body = application(prepared, UUID.randomUUID().toString());
        var applied = call(actor, HttpMethod.POST, url, body);
        assertEquals(HttpStatus.OK, applied.getStatusCode());
        assertEquals("SUCCEEDED", applied.getBody().path("status").asText());
        assertEquals(prepared.path("receiptId"), applied.getBody().path("receiptId"));
        assertEquals(applied.getBody(), call(actor, HttpMethod.POST, url, body).getBody());
        var dataset = call(actor, HttpMethod.GET, "/data/datasets/" + prepared.path("resourceId").asText(), null);
        assertEquals(HttpStatus.OK, dataset.getStatusCode());
        assertEquals("native-dense-1", dataset.getBody().path("storageProfile").asText());
        var decoded = com.voltcanary.vcl.VclDatasetDescriptions.parse(dataset.getBody().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(prepared.path("resourceId").asText(), decoded.id().toString());
        assertEquals(dataset.getBody().path("schemaDigest").asText(), decoded.schemaDigest());
        assertTrue(dataset.getBody().path("liveQueryReady").asBoolean());
        assertEquals("Customer table", dataset.getBody().at("/definition/label").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_mutation_receipt WHERE review_id=? AND status='SUCCEEDED'", Integer.class,
                UUID.fromString(prepared.path("reviewId").asText())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_outbox WHERE id=?", Integer.class, UUID.fromString(prepared.path("receiptId").asText())));
        var summaries = call(actor, HttpMethod.GET, "/data/datasets", null);
        assertEquals(HttpStatus.OK, summaries.getStatusCode());
        assertEquals(1, summaries.getBody().size());
        assertEquals(prepared.path("resourceId"), summaries.getBody().at("/0/id"));
        assertEquals(0, call(actor(), HttpMethod.GET, "/data/datasets", null).getBody().size());
        var language = call(actor, HttpMethod.GET, "/data/language", null);
        var registry = new com.voltcanary.vcl.VclRegistry(new com.voltcanary.vcl.VclParser());
        assertEquals(registry.capabilities(), language.getBody().path("registry"));
        assertEquals("native-live-sql-1", language.getBody().at("/nativeQuery/profile").asText());
        assertFalse(language.getBody().path("ruleExecutionReady").asBoolean());
    }

    @Test
    void changedPreviewInvalidatesPreviousConfirmationAndForeignActorsSeeNoReviewOrDataset() {
        var actor = actor();
        var reservation = reserve(actor);
        var original = prepare(actor, reservation, "Original");
        var changed = prepare(actor, reservation, "Renamed");
        assertNotEquals(original.path("reviewDigest"), changed.path("reviewDigest"));
        assertEquals(original.path("revision").asLong() + 1, changed.path("revision").asLong());
        String path = "/data/reviews/" + original.path("reviewId").asText();
        var stale = call(actor, HttpMethod.POST, path + "/apply", application(original, "stale"));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        assertEquals("REVIEW_CONFLICT", stale.getBody().path("code").asText());
        var other = actor();
        assertEquals(HttpStatus.NOT_FOUND, call(other, HttpMethod.GET, path, null).getStatusCode());
        assertEquals(HttpStatus.OK, call(actor, HttpMethod.POST, path + "/apply", application(changed, "current")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, call(other, HttpMethod.GET, "/data/datasets/" + changed.path("resourceId").asText(), null).getStatusCode());
    }

    @Test
    void lostCompletionAfterProvisioningReconcilesTheSameDurableReceipt() {
        var actor = actor();
        var prepared = prepare(actor, reserve(actor), "Recoverable");
        doThrow(new VclException("NATIVE_STORAGE_UNAVAILABLE", "")).doCallRealMethod().when(reviews)
                .complete(any(UUID.class), any(UUID.class), any(UUID.class), any(NativeDataset.class));
        String path = "/data/reviews/" + prepared.path("reviewId").asText();
        var unknown = call(actor, HttpMethod.POST, path + "/apply", application(prepared, "recover"));
        assertEquals(HttpStatus.ACCEPTED, unknown.getStatusCode());
        assertEquals("UNKNOWN", unknown.getBody().path("status").asText());
        var recovered = call(actor, HttpMethod.POST, path + "/reconcile", "{}");
        assertEquals(HttpStatus.OK, recovered.getStatusCode());
        assertEquals("SUCCEEDED", recovered.getBody().path("status").asText());
        assertEquals(unknown.getBody().path("receiptId"), recovered.getBody().path("receiptId"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_dataset WHERE id=?", Integer.class, UUID.fromString(prepared.path("resourceId").asText())));
    }

    @Test
    void malformedBodiesAndUnreservedIdsCannotReachProvisioning() {
        var actor = actor();
        assertEquals(HttpStatus.BAD_REQUEST, call(actor, HttpMethod.POST, "/data/review-reservations",
                "{\"kind\":\"COLLECTION\",\"kind\":\"COLLECTION\",\"fieldKeys\":[\"name\"],\"idempotencyKey\":\"bad\"}").getStatusCode());
        var reservation = reserve(actor);
        var body = preparation(reservation, "Invalid");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.at("/document/spec")).put("id", UUID.randomUUID().toString());
        var result = call(actor, HttpMethod.POST, "/data/dataset-reviews", body.toString());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, result.getStatusCode());
        assertEquals("RESERVED_RESOURCE_MISMATCH", result.getBody().path("code").asText());
        var prepared = prepare(actor, reservation, "Valid");
        com.fasterxml.jackson.databind.node.ObjectNode extra;
        try { extra = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(application(prepared, "bad-approval")); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        extra.put("approved", true);
        assertEquals(HttpStatus.BAD_REQUEST, call(actor, HttpMethod.POST, "/data/reviews/" + prepared.path("reviewId").asText() + "/apply", extra.toString()).getStatusCode());
    }

    @Test
    void deletedAccountArchivesNativeResourcesEvenWhenItHasNoLegacyDevices() {
        var actor = actor();
        var account = accounts.findByUserId(actor.id()).orElseThrow().getId();
        var prepared = prepare(actor, reserve(actor), "Archived");
        var path = "/data/reviews/" + prepared.path("reviewId").asText();
        assertEquals(HttpStatus.OK, call(actor, HttpMethod.POST, path + "/apply", application(prepared, "create")).getStatusCode());
        var pending = reserve(actor);
        var pendingRow = rowReservation(actor, prepared, UUID.randomUUID().toString());
        accounts.syncUser(actor.id(), actor.email(), AccountStatus.DELETED);
        assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM cfg_dataset WHERE id=?", String.class, UUID.fromString(prepared.path("resourceId").asText())));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM cfg_collection_review WHERE id=?", String.class, UUID.fromString(pending.path("reviewId").asText())));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM cfg_row_review WHERE id=?", String.class, UUID.fromString(pendingRow.path("reviewId").asText())));
        assertEquals(HttpStatus.FORBIDDEN, call(actor, HttpMethod.GET, "/data/datasets/" + prepared.path("resourceId").asText(), null).getStatusCode());
        accounts.syncUser(actor.id(), actor.email(), AccountStatus.ACTIVE);
        assertTrue(catalog.ready(account, UUID.fromString(prepared.path("resourceId").asText()), 1).isEmpty());
    }

    private AuthenticatedUser actor() {
        var actor = new AuthenticatedUser(UUID.randomUUID(), "native@example.invalid", "USER");
        accounts.currentAccount(actor);
        return actor;
    }
    private JsonNode reserve(AuthenticatedUser actor) {
        var response = call(actor, HttpMethod.POST, "/data/review-reservations", """
                {"kind":"COLLECTION","fieldKeys":["name"],"idempotencyKey":"%s"}
                """.formatted(UUID.randomUUID()));
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody().toString());
        return response.getBody();
    }
    private JsonNode prepare(AuthenticatedUser actor, JsonNode reservation, String label) {
        var response = call(actor, HttpMethod.POST, "/data/dataset-reviews", preparation(reservation, label).toString());
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody().toString());
        return response.getBody();
    }
    private JsonNode preparation(JsonNode reservation, String label) {
        var root = mapper.createObjectNode().put("reservationId", reservation.path("reviewId").asText());
        var document = root.putObject("document").put("language", "VCL/1").put("kind", "COLLECTION");
        var spec = document.putObject("spec").put("id", reservation.path("resourceId").asText()).put("revision", 1).put("label", label);
        spec.putArray("fields").addObject().put("id", reservation.at("/fieldIdsByKey/name").asText()).put("label", "Name").put("type", "TEXT").put("nullable", false).put("maxLength", 120);
        spec.putArray("keyFieldIds");
        spec.putObject("storage").put("mode", "NATIVE").put("history", "NONE").putArray("indexedFieldIds");
        return root;
    }
    private String application(JsonNode review, String key) {
        return mapper.createObjectNode().put("reviewRevision", review.path("revision").asLong()).put("reviewDigest", review.path("reviewDigest").asText()).put("idempotencyKey", key).toString();
    }
    private ResponseEntity<JsonNode> call(AuthenticatedUser actor, HttpMethod method, String path, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(SignedUserTokens.create(actor.id(), actor.email(), actor.role()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}

