package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import java.util.Map;
import java.util.UUID;

final class NativeAuthoringFixtures {

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final JsonNode FIXTURE;
    static final VclDocument DOCUMENT;
    static final String DIGEST = "a".repeat(64);
    static final String KEY = "ai-native-" + "c".repeat(64);
    static final String AUTH = "Bearer signed-fixture";

    static {
        try (
            var input = NativeAuthoringFixtures.class.getResourceAsStream(
                "/native-authoring/native-table-authoring.json"
            )
        ) {
            FIXTURE = JSON.readTree(input);
            DOCUMENT = new VclParser().parse(FIXTURE.path("document").toString());
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    static UUID id(String field) {
        return UUID.fromString(FIXTURE.path("reservation").path(field).asText());
    }

    static ObjectNode view(String status) {
        var value = (ObjectNode) FIXTURE.path("reservation").deepCopy();
        value
            .put("status", status)
            .put("revision", 1)
            .put("reviewDigest", DIGEST)
            .put("semanticDigest", VclCanonical.semanticDigest(DOCUMENT));
        value.set("typedDiff", DOCUMENT.spec());
        return value;
    }

    static ObjectNode dataset() {
        String generation = "80000000-0000-4000-8000-000000000200";
        var value = JSON.createObjectNode()
            .put("id", id("resourceId").toString())
            .put("schemaRevision", 1)
            .put("schemaDigest", VclCanonical.documentDigest(DOCUMENT))
            .put("accessScopeDigest", "b".repeat(64))
            .put("generation", generation)
            .put("dataVersion", 1)
            .put("storageProfile", "native-dense-1")
            .put("liveQueryReady", true)
            .put("historyReady", false);
        var definition = (ObjectNode) DOCUMENT.spec();
        definition
            .put("generation", generation)
            .put("dataVersion", 1)
            .putObject("systemFields")
            .put("recordIdFieldId", "80000000-0000-4000-8000-000000000201");
        value.set("definition", definition);
        value.set("readyIndexedFields", DOCUMENT.spec().at("/storage/indexedFieldIds"));
        value.putObject("queryCapabilities").put("profile", "native-live-sql-1");
        return value;
    }

    static ObjectNode language() {
        var value = JSON.createObjectNode();
        value.set("registry", new VclRegistry(new VclParser()).capabilities());
        value.putObject("nativeQuery").put("profile", "native-live-sql-1");
        value.put("ruleExecutionReady", false);
        return value;
    }

    static AgentAction action() {
        return new AgentAction(
            UUID.randomUUID(),
            AgentTool.CREATE_NATIVE_TABLE,
            "Create visits",
            "Review eleven fields",
            true,
            Map.of(
                "protocol",
                NativeTableSkill.PROTOCOL,
                "ownerReviewId",
                id("reviewId").toString(),
                "ownerReviewRevision",
                1,
                "ownerReviewDigest",
                DIGEST,
                "ownerReceiptId",
                id("receiptId").toString(),
                "datasetId",
                id("resourceId").toString(),
                "ownerIdempotencyKey",
                KEY,
                "semanticDigest",
                VclCanonical.semanticDigest(DOCUMENT),
                "document",
                JSON.convertValue(DOCUMENT.json(), Map.class)
            )
        );
    }

    private NativeAuthoringFixtures() {}
}
