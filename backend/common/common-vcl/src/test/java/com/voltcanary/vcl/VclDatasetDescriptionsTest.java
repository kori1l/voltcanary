package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VclDatasetDescriptionsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final VclParser PARSER = new VclParser();
    private static final UUID ID = UUID.randomUUID();
    private static final UUID FIELD = UUID.randomUUID();

    @Test
    void strictNativeOwnerDescriptionRoundTripsWithoutExposingMutableMetadata() {
        var original = description();
        var decoded = VclDatasetDescriptions.parse(bytes(original));
        assertEquals(ID, decoded.id());
        assertEquals(original.path("schemaDigest").asText(), decoded.schemaDigest());
        ((ObjectNode) decoded.definition()).put("label", "changed");
        assertEquals("Native", decoded.definition().path("label").asText());
    }

    @Test
    void forgedIdentityDigestsIndexReadinessSystemKeysAndPrimitiveTypesAreRejected() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("schemaDigest", "c".repeat(64)),
                value -> value.put("id", UUID.randomUUID().toString()),
                value -> value.put("schemaRevision", 1.5),
                value -> value.put("dataVersion", 1.5),
                value -> value.put("generation", UUID.randomUUID().toString()),
                value -> value.put("storageProfile", "unknown"),
                value -> value.put("sql", "private SQL"),
                value -> value.remove("historyReady"),
                value -> value.putArray("definition"),
                value -> ((ObjectNode) value.at("/definition/systemFields")).put("recordIdFieldId", FIELD.toString()),
                value -> value.putArray("readyIndexedFields").add(UUID.randomUUID().toString()))) {
            var value = description();
            change.accept(value);
            assertInvalid(bytes(value));
        }
    }

    @Test
    void duplicateUnsafeTrailingAndOversizedWireJsonNeverBecomesMetadata() {
        String original = description().toString();
        assertInvalid(original.replace("\"historyReady\":false", "\"historyReady\":false,\"historyReady\":false").getBytes(StandardCharsets.UTF_8));
        assertInvalid((original + "{}").getBytes(StandardCharsets.UTF_8));
        assertInvalid(original.replace("\"dataVersion\":1", "\"dataVersion\":9007199254740993").getBytes(StandardCharsets.UTF_8));
        assertInvalid(new byte[]{(byte) 0xc3, (byte) 0x28});
        assertInvalid(new byte[VclParser.MAX_BYTES + 1]);
    }

    private void assertInvalid(byte[] bytes) {
        assertEquals("OWNER_SNAPSHOT_INVALID", assertThrows(VclException.class, () -> VclDatasetDescriptions.parse(bytes)).code());
    }

    private byte[] bytes(ObjectNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }

    private ObjectNode description() {
        var document = MAPPER.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
        var spec = document.putObject("spec").put("id", ID.toString()).put("revision", 1).put("label", "Native");
        spec.putArray("fields").addObject().put("id", FIELD.toString()).put("label", "Name").put("type", "TEXT").put("maxLength", 120).put("nullable", true);
        spec.putArray("keyFieldIds");
        spec.putObject("storage").put("mode", "NATIVE").put("history", "NONE").putArray("indexedFieldIds");
        var parsed = PARSER.parse(document.toString());
        UUID generation = UUID.randomUUID();
        var definition = (ObjectNode) parsed.spec();
        definition.put("generation", generation.toString()).put("dataVersion", 1);
        definition.putObject("systemFields").put("recordIdFieldId", UUID.randomUUID().toString());
        var response = MAPPER.createObjectNode().put("id", ID.toString()).put("schemaRevision", 1)
                .put("schemaDigest", VclCanonical.documentDigest(parsed)).put("accessScopeDigest", "a".repeat(64))
                .put("generation", generation.toString()).put("dataVersion", 1).put("storageProfile", "native-dense-1")
                .put("liveQueryReady", true).put("historyReady", false);
        response.set("definition", definition);
        response.putArray("readyIndexedFields");
        response.putObject("queryCapabilities").put("profile", "native-live-sql-1");
        return response;
    }
}
