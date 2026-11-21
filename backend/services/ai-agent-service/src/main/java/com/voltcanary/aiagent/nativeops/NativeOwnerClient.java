package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Bounded signed ordinary owner calls. No service identity fallback, secret persistence or implicit retry. */
@Component
public class NativeOwnerClient {
    private static final Set<String> VIEW_FIELDS = Set.of("reviewId", "revision", "status", "expiresAt", "resourceId", "fieldIdsByKey",
            "semanticDigest", "reviewDigest", "typedDiff", "receiptId", "outcomeCode");
    private final RestClient client;
    private final VclParser parser = new VclParser();
    private final JsonNode registryContract = new VclRegistry(parser).capabilities();

    public record Review(UUID id, long revision, String status, Instant expiresAt, UUID datasetId, Map<String, UUID> fieldIds,
            String semanticDigest, String digest, JsonNode diff, UUID receiptId) {
        public Review {
            fieldIds = Map.copyOf(fieldIds);
            diff = diff == null ? null : diff.deepCopy();
        }
        @Override public JsonNode diff() { return diff == null ? null : diff.deepCopy(); }
        @Override public String toString() { return "NativeOwnerReview[status=" + status + "]"; }
    }

    public NativeOwnerClient(RestClient.Builder builder, AiAgentProperties properties) {
        var transport = new Transport();
        transport.setConnectTimeout(Duration.ofSeconds(5));
        transport.setReadTimeout(Duration.ofSeconds(5));
        client = builder.clone().baseUrl(properties.getServices().getDeviceBaseUrl()).requestFactory(transport).build();
    }

    public JsonNode language(String authorization) {
        var language = get("/data/language", authorization);
        require(language.isObject() && language.size() == 3 && language.has("registry") && language.has("nativeQuery") && language.has("ruleExecutionReady"), "NATIVE_LANGUAGE_INVALID");
        require(language.path("registry").equals(registryContract)
                && language.path("nativeQuery").path("profile").asText().equals("native-live-sql-1")
                && language.path("ruleExecutionReady").isBoolean() && !language.path("ruleExecutionReady").asBoolean(), "NATIVE_PROFILE_UNAVAILABLE");
        return language;
    }

    public Review reserve(String authorization, String key, List<String> fields) {
        return review(post("/data/review-reservations", authorization, Map.of("kind", "COLLECTION", "fieldKeys", fields, "idempotencyKey", key)));
    }
    public Review prepare(String authorization, UUID reservationId, JsonNode document) {
        require(document != null, "NATIVE_DOCUMENT_REQUIRED");
        var collection = parser.parse(document.toString());
        require(collection.kind() == com.voltcanary.vcl.VclDocument.Kind.COLLECTION, "NATIVE_COLLECTION_REQUIRED");
        return review(post("/data/dataset-reviews", authorization,
                Map.of("reservationId", reservationId, "document", collection.json())));
    }
    public Review inspect(String authorization, UUID reviewId) { return review(get("/data/reviews/" + reviewId, authorization)); }
    public Review apply(String authorization, UUID reviewId, long revision, String digest, String key) {
        return review(post("/data/reviews/" + reviewId + "/apply", authorization,
                Map.of("reviewRevision", revision, "reviewDigest", digest, "idempotencyKey", key)));
    }
    public Review reconcile(String authorization, UUID reviewId) { return review(post("/data/reviews/" + reviewId + "/reconcile", authorization, Map.of())); }
    public JsonNode dataset(String authorization, UUID id) { return get("/data/datasets/" + id + "?schemaRevision=1", authorization); }

    public JsonNode previewMapping(String authorization, com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope,
            UUID bindingId, long revision, UUID fieldId, JsonNode conversion, JsonNode sample) {
        var source = sourceCapture(authorization, scope, bindingId, revision).source();
        require(source.sourceActive(), "NATIVE_SOURCE_INACTIVE");
        require(java.util.stream.StreamSupport.stream(source.definition().path("fields").spliterator(), false)
                .anyMatch(field -> fieldId.toString().equals(field.path("id").textValue())), "NATIVE_MAPPING_FIELD_UNAVAILABLE");
        // The owner can accept raw TEXT for a field whose published type reflects a previously applied conversion.
        require(sample.isBoolean() || sample.isTextual(), "NATIVE_MAPPING_SAMPLE_INVALID");
        var expectedLiteral = com.voltcanary.vcl.VclSourceMappingReview.convert(
                sample.isBoolean() ? "BOOLEAN" : "TEXT", conversion, sample);
        var result = post("/data/source-bindings/" + bindingId + "/mapping-preview", authorization,
                Map.of("schemaRevision", revision, "fieldId", fieldId, "conversion", conversion, "sample", sample));
        String digest = com.voltcanary.vcl.VclSourceMappingReview.digest(
                source.definitionDigest(), source.accessScopeDigest(), fieldId, conversion);
        require(result.isObject() && result.size() == 5 && bindingId.equals(uuid(result.path("bindingId")))
                && result.path("schemaRevision").isIntegralNumber() && result.path("schemaRevision").canConvertToLong()
                && result.path("schemaRevision").longValue() == revision
                && source.snapshotId().equals(uuid(result.path("schemaSnapshotId")))
                && digest.equals(result.path("mappingDigest").textValue()) && expectedLiteral.equals(result.path("literal")),
                "NATIVE_MAPPING_PREVIEW_CHANGED");
        return result;
    }

    public com.voltcanary.vcl.VclSourceCaptureDescription sourceCapture(String authorization,
            com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope, UUID bindingId, long revision) {
        require(revision >= 1 && revision <= 9_007_199_254_740_991L, "NATIVE_SOURCE_REVISION_INVALID");
        var response = get("/data/source-bindings/" + bindingId + "/capture?schemaRevision=" + revision, authorization);
        var capture = com.voltcanary.vcl.VclSourceCaptureDescription.parse(
                response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var source = capture.source();
        require(bindingId.equals(source.id()) && revision == source.schemaRevision()
                && scope.accountId().equals(source.accountId()) && scope.userId().equals(source.actorId()),
                "NATIVE_SOURCE_SCOPE_CHANGED");
        return capture;
    }

    public JsonNode confirmMapping(String authorization, com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope,
            UUID bindingId, long revision, UUID fieldId, JsonNode conversion, String reviewedDigest) {
        var base = mappingSource(authorization, scope, bindingId, revision);
        require(base.sourceActive() && com.voltcanary.vcl.VclSourceMappingReview.digest(
                base.definitionDigest(), base.accessScopeDigest(), fieldId, conversion).equals(reviewedDigest),
                "NATIVE_MAPPING_REVIEW_CHANGED");
        com.voltcanary.vcl.VclBooleanTokens.parse(conversion, "/conversion");
        try {
            var receipt = post("/data/source-bindings/" + bindingId + "/mapping-confirmation", authorization,
                    Map.of("schemaRevision", revision, "fieldId", fieldId, "conversion", conversion, "mappingDigest", reviewedDigest));
            return verifyMappingReceipt(authorization, scope, base, fieldId, reviewedDigest, receipt);
        } catch (RuntimeException unavailable) {
            throw new Uncertain("NATIVE_MAPPING_OUTCOME_UNVERIFIED");
        }
    }

    public JsonNode inspectMapping(String authorization, com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope,
            UUID bindingId, long revision, UUID fieldId, String reviewedDigest) {
        try {
            require(reviewedDigest != null && reviewedDigest.matches("[0-9a-f]{64}"), "NATIVE_MAPPING_REVIEW_CHANGED");
            var base = mappingSource(authorization, scope, bindingId, revision);
            var receipt = get("/data/source-bindings/" + bindingId + "/mapping-receipts/" + reviewedDigest
                    + "?schemaRevision=" + revision, authorization);
            return verifyMappingReceipt(authorization, scope, base, fieldId, reviewedDigest, receipt);
        } catch (RuntimeException unavailable) {
            throw new Uncertain("NATIVE_MAPPING_OUTCOME_UNVERIFIED");
        }
    }

    private JsonNode verifyMappingReceipt(String authorization, com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope,
            com.voltcanary.vcl.VclSourceBindingDescription base, UUID fieldId, String reviewedDigest, JsonNode receipt) {
        var appliedRevision = receipt.path("schemaRevision");
        require(receipt.isObject() && receipt.size() == 7 && base.id().equals(uuid(receipt.path("bindingId")))
                    && base.deviceId().equals(uuid(receipt.path("deviceId"))) && fieldId.equals(uuid(receipt.path("fieldId")))
                    && reviewedDigest.equals(receipt.path("mappingDigest").textValue())
                    && appliedRevision.isIntegralNumber() && appliedRevision.canConvertToLong()
                    && appliedRevision.longValue() > base.schemaRevision(), "NATIVE_MAPPING_RECEIPT_CHANGED");
        var applied = mappingSource(authorization, scope, base.id(), appliedRevision.longValue());
        require(applied.deviceId().equals(base.deviceId()) && applied.snapshotId().equals(uuid(receipt.path("schemaSnapshotId")))
                    && applied.definitionDigest().equals(receipt.path("definitionDigest").textValue())
                    && java.util.stream.StreamSupport.stream(applied.definition().path("fields").spliterator(), false)
                            .anyMatch(field -> fieldId.toString().equals(field.path("id").textValue())
                                    && "BOOLEAN".equals(field.path("type").textValue())), "NATIVE_MAPPING_READBACK_CHANGED");
        return receipt;
    }

    private com.voltcanary.vcl.VclSourceBindingDescription mappingSource(String authorization,
            com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope, UUID bindingId, long revision) {
        require(revision >= 1 && revision <= 9_007_199_254_740_991L, "NATIVE_SOURCE_REVISION_INVALID");
        var response = get("/data/source-bindings/" + bindingId + "?schemaRevision=" + revision, authorization);
        var source = com.voltcanary.vcl.VclSourceBindingDescription.parse(
                response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        require(bindingId.equals(source.id()) && revision == source.schemaRevision()
                && scope.accountId().equals(source.accountId()) && scope.userId().equals(source.actorId()), "NATIVE_SOURCE_SCOPE_CHANGED");
        return source;
    }

    private JsonNode get(String path, String authorization) {
        auth(authorization);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        return client.get().uri(path).header("Authorization", authorization).exchange((request, response) -> body(response, deadline));
    }
    private JsonNode post(String path, String authorization, Object body) {
        auth(authorization);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        return client.post().uri(path).header("Authorization", authorization).body(body).exchange((request, response) -> body(response, deadline));
    }
    private JsonNode body(org.springframework.http.client.ClientHttpResponse response, long deadline) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 401 || status == 403 || status == 404) throw new IllegalArgumentException("NATIVE_OWNER_ACCESS_UNAVAILABLE");
        if (status != 200 && status != 201 && status != 202) {
            if (status >= 400 && status < 500) throw new IllegalArgumentException("NATIVE_OWNER_REJECTED");
            throw new Uncertain("NATIVE_OWNER_UNAVAILABLE");
        }
        var output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() <= VclParser.MAX_BYTES) {
            if (System.nanoTime() - deadline >= 0) throw new Uncertain("NATIVE_OWNER_RESPONSE_TIMEOUT");
            int count = response.getBody().read(buffer, 0, Math.min(buffer.length, VclParser.MAX_BYTES + 1 - output.size()));
            if (count < 0) break;
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length > VclParser.MAX_BYTES) throw new Uncertain("NATIVE_OWNER_RESPONSE_LIMIT");
        try { return parser.readJson(bytes); }
        catch (IllegalArgumentException invalid) { throw new Uncertain("NATIVE_OWNER_RESPONSE_INVALID"); }
    }

    private Review review(JsonNode value) {
        try { return decodeReview(value); }
        catch (IllegalArgumentException invalid) { throw new Uncertain("NATIVE_REVIEW_INVALID"); }
    }
    private Review decodeReview(JsonNode value) {
        require(value.isObject(), "NATIVE_REVIEW_INVALID");
        value.fieldNames().forEachRemaining(field -> require(VIEW_FIELDS.contains(field), "NATIVE_REVIEW_INVALID"));
        require(value.path("revision").isIntegralNumber() && value.path("revision").canConvertToLong() && value.path("revision").longValue() >= 0,
                "NATIVE_REVIEW_INVALID");
        require(Set.of("RESERVED", "PREPARED", "APPLYING", "UNKNOWN", "SUCCEEDED", "EXPIRED", "CANCELLED", "FAILED").contains(value.path("status").asText()), "NATIVE_REVIEW_INVALID");
        var outcome = value.path("outcomeCode");
        require(outcome.isMissingNode() || outcome.isNull()
                || outcome.isTextual() && outcome.textValue().matches("[A-Z][A-Z0-9_]{0,79}"), "NATIVE_REVIEW_INVALID");
        require(!"SUCCEEDED".equals(value.path("status").asText()) || !value.hasNonNull("outcomeCode"), "NATIVE_REVIEW_INVALID");
        var fields = new LinkedHashMap<String, UUID>();
        require(value.path("fieldIdsByKey").isObject() && value.path("fieldIdsByKey").size() >= 1 && value.path("fieldIdsByKey").size() <= 64, "NATIVE_REVIEW_INVALID");
        value.path("fieldIdsByKey").fields().forEachRemaining(field -> {
            require(field.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"), "NATIVE_REVIEW_INVALID");
            fields.put(field.getKey(), uuid(field.getValue()));
        });
        String digest = optionalDigest(value.path("reviewDigest"));
        String semantic = optionalDigest(value.path("semanticDigest"));
        JsonNode diff = value.path("typedDiff");
        if (digest != null) require(semantic != null && diff.isObject(), "NATIVE_REVIEW_INVALID");
        try {
            return new Review(uuid(value.path("reviewId")), value.path("revision").longValue(), value.path("status").asText(),
                    Instant.parse(value.path("expiresAt").asText()), uuid(value.path("resourceId")), fields, semantic, digest,
                    diff.isMissingNode() || diff.isNull() ? null : diff, uuid(value.path("receiptId")));
        } catch (IllegalArgumentException invalid) { throw new Uncertain("NATIVE_REVIEW_INVALID"); }
    }
    private static UUID uuid(JsonNode node) {
        require(node.isTextual() && node.textValue().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "NATIVE_REVIEW_INVALID");
        return UUID.fromString(node.textValue());
    }
    private static String optionalDigest(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        require(node.isTextual() && node.textValue().matches("[0-9a-f]{64}"), "NATIVE_REVIEW_INVALID");
        return node.textValue();
    }
    private static void auth(String value) { require(value != null && value.startsWith("Bearer ") && !value.substring(7).isBlank(), "NATIVE_AUTHORIZATION_REQUIRED"); }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
    public static final class Uncertain extends RuntimeException {
        public Uncertain(String code) { super(code); }
    }
    private static final class Transport extends SimpleClientHttpRequestFactory {
        @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
