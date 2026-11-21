package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDatasetDescriptions;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclParser;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Confirmation is owned by the existing reviewed-plan boundary; only its exact stored native review is dispatched. */
@Component
@RequiredArgsConstructor
public class NativeTableExecutor {
    private static final Set<String> PARAMETERS = Set.of("protocol", "ownerReviewId", "ownerReviewRevision", "ownerReviewDigest", "ownerReceiptId",
            "datasetId", "ownerIdempotencyKey", "semanticDigest", "document");
    private final NativeOwnerClient owner;
    private final ObjectMapper mapper;
    private final VclParser parser = new VclParser();

    public Map<String, Object> execute(AgentAction action, String authorization) {
        var expected = expected(action);
        var before = owner.inspect(authorization, expected.reviewId());
        check(expected, before);
        if (before.status().equals("SUCCEEDED")) return committed(expected, before, authorization);
        require(before.status().equals("PREPARED") || before.status().equals("UNKNOWN") || before.status().equals("APPLYING"), "NATIVE_REVIEW_NOT_APPLICABLE");
        var result = before.status().equals("PREPARED")
                ? owner.apply(authorization, before.id(), expected.revision(), expected.reviewDigest(), expected.key())
                : owner.reconcile(authorization, before.id());
        checkResult(expected, result);
        return committed(expected, result, authorization);
    }

    public Map<String, Object> executeMapping(AgentAction action, String authorization, boolean inspect) {
        require(action.tool() == AgentTool.APPLY_SOURCE_MAPPING && action.requiresConfirmation()
                && action.parameters().keySet().equals(Set.of("accountId", "userId", "bindingId", "schemaRevision",
                        "fieldId", "conversion", "mappingDigest", "mappingExample")), "NATIVE_MAPPING_REVIEW_REQUIRED");
        var value = mapper.valueToTree(action.parameters());
        var revision = value.path("schemaRevision");
        require(revision.isIntegralNumber() && revision.canConvertToLong(), "NATIVE_MAPPING_REVIEW_REQUIRED");
        var scope = new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(
                UUID.fromString(value.path("userId").asText()), UUID.fromString(value.path("accountId").asText()));
        var mapping = new ReviewedSourceMapping(UUID.fromString(value.path("bindingId").asText()), revision.longValue(),
                UUID.fromString(value.path("fieldId").asText()), value.path("conversion"), value.path("mappingDigest").asText());
        var example = value.path("mappingExample");
        require(example.isObject() && example.size() == 2
                && com.voltcanary.vcl.VclSourceMappingReview.convert("TEXT", mapping.conversion(), example.path("sample"))
                        .equals(example.path("literal")), "NATIVE_MAPPING_EXAMPLE_CHANGED");
        var receipt = inspect ? mapping.inspect(owner, authorization, scope) : mapping.confirm(owner, authorization, scope);
        return mapper.convertValue(receipt, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    public Map<String, Object> reconcile(AgentAction action, String authorization) {
        // Loss of receipt visibility is not proof of no effect; never reserve/prepare a replacement here.
        try {
            var expected = expected(action);
            var current = owner.inspect(authorization, expected.reviewId());
            check(expected, current);
            if (!current.status().equals("SUCCEEDED")) {
                if (!Set.of("APPLYING", "UNKNOWN").contains(current.status())) throw new NativeOwnerClient.Uncertain("NATIVE_OUTCOME_UNRESOLVED");
                current = owner.reconcile(authorization, current.id());
            }
            checkResult(expected, current);
            return committed(expected, current, authorization);
        } catch (IllegalArgumentException unavailable) { throw new NativeOwnerClient.Uncertain("NATIVE_OUTCOME_UNAVAILABLE"); }
    }

    /** Expired AI reviews can recover a positive receipt using GETs only, never renew dispatch authority. */
    public Map<String, Object> inspectOutcome(AgentAction action, String authorization) {
        try {
            var expected = expected(action);
            var review = owner.inspect(authorization, expected.reviewId());
            checkResult(expected, review);
            return committed(expected, review, authorization);
        } catch (IllegalArgumentException unavailable) { throw new NativeOwnerClient.Uncertain("NATIVE_OUTCOME_UNAVAILABLE"); }
    }

    private record Expected(UUID reviewId, UUID datasetId, UUID receiptId, long revision, String reviewDigest, String key, VclDocument document) { }
    private Expected expected(AgentAction action) {
        require(action.tool() == AgentTool.CREATE_NATIVE_TABLE && action.requiresConfirmation()
                && action.parameters().keySet().equals(PARAMETERS), "NATIVE_REVIEW_REQUIRED");
        var values = mapper.valueToTree(action.parameters());
        require(values.path("protocol").asText().equals(NativeTableSkill.PROTOCOL)
                && values.path("ownerReviewRevision").isIntegralNumber() && values.path("ownerReviewRevision").canConvertToLong()
                && values.path("ownerReviewRevision").longValue() >= 1
                && values.path("ownerReviewDigest").asText().matches("[0-9a-f]{64}")
                && values.path("ownerIdempotencyKey").asText().matches("ai-native-[0-9a-f]{64}"), "NATIVE_REVIEW_REQUIRED");
        var document = parser.parse(values.path("document").toString());
        require(document.kind() == VclDocument.Kind.COLLECTION
                && VclCanonical.semanticDigest(document).equals(values.path("semanticDigest").asText()), "NATIVE_DOCUMENT_CHANGED");
        UUID dataset = UUID.fromString(values.path("datasetId").asText());
        require(dataset.toString().equals(document.spec().path("id").asText()), "NATIVE_DATASET_CHANGED");
        return new Expected(UUID.fromString(values.path("ownerReviewId").asText()), dataset, UUID.fromString(values.path("ownerReceiptId").asText()),
                values.path("ownerReviewRevision").longValue(), values.path("ownerReviewDigest").asText(), values.path("ownerIdempotencyKey").asText(), document);
    }

    private static void check(Expected expected, NativeOwnerClient.Review review) {
        require(expected.reviewId().equals(review.id()) && expected.datasetId().equals(review.datasetId())
                && expected.receiptId().equals(review.receiptId()) && expected.revision() == review.revision()
                && expected.reviewDigest().equals(review.digest()) && VclCanonical.semanticDigest(expected.document()).equals(review.semanticDigest())
                && expected.document().spec().equals(review.diff()), "NATIVE_OWNER_REVIEW_CHANGED");
        var reviewedFields = new java.util.HashSet<UUID>();
        expected.document().spec().path("fields").forEach(field -> reviewedFields.add(UUID.fromString(field.path("id").asText())));
        require(review.fieldIds().size() == reviewedFields.size()
                && reviewedFields.equals(Set.copyOf(review.fieldIds().values())), "NATIVE_OWNER_FIELDS_CHANGED");
    }
    private static void checkResult(Expected expected, NativeOwnerClient.Review review) {
        try { check(expected, review); }
        catch (IllegalArgumentException changed) { throw new NativeOwnerClient.Uncertain("NATIVE_OWNER_RESULT_CHANGED"); }
    }
    private Map<String, Object> committed(Expected expected, NativeOwnerClient.Review review, String authorization) {
        if (!review.status().equals("SUCCEEDED")) throw new NativeOwnerClient.Uncertain("NATIVE_OUTCOME_UNRESOLVED");
        try {
            var response = owner.dataset(authorization, expected.datasetId());
            var actual = VclDatasetDescriptions.parse(response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            require(actual.id().equals(expected.datasetId()) && actual.schemaRevision() == 1
                    && actual.liveQueryReady(), "NATIVE_CREATED_RESOURCE_INVALID");
            String digest = actual.schemaDigest();
            require(digest.equals(VclCanonical.documentDigest(expected.document())), "NATIVE_CREATED_SCHEMA_CHANGED");
            return Map.of("datasetId", expected.datasetId().toString(), "ownerReviewId", expected.reviewId().toString(),
                    "receiptId", expected.receiptId().toString(), "schemaRevision", 1, "schemaDigest", digest,
                    "route", "/data?datasetId=" + expected.datasetId(), "setupStatus", "NATIVE_TABLE_READY");
        } catch (RuntimeException unavailable) { throw new NativeOwnerClient.Uncertain("NATIVE_CREATED_RESOURCE_UNVERIFIED"); }
    }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
}
