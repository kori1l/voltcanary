package com.voltcanary.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class VclConformanceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode examples = read("configurable-operations-language.examples.json");
    private final VclParser parser = new VclParser();
    private final VclCompiler compiler = new VclCompiler(
        new VclRegistry(parser),
        VclUnits.standard()
    );
    private final VclMetadata metadata = new Fixtures();

    @TestFactory
    Stream<DynamicTest> positiveDocuments() {
        return java.util.stream.IntStream.range(0, examples.path("documents").size()).mapToObj(i ->
            DynamicTest.dynamicTest("document " + i, () -> {
                var result = compiler.compile(
                    parser.parse(examples.path("documents").get(i).toString()),
                    metadata
                );
                assertEquals(64, result.documentDigest().length());
                assertEquals("a".repeat(64), result.accessScopeDigest());
            })
        );
    }

    @TestFactory
    Stream<DynamicTest> negativeDocuments() {
        var cases = read("configurable-operations-language.conformance.json").path("cases");
        return java.util.stream.StreamSupport.stream(cases.spliterator(), false).map(test ->
            DynamicTest.dynamicTest(test.path("id").asText(), () -> {
                var document = patch(test);
                var error = assertThrows(VclException.class, () ->
                    compiler.compile(parser.parse(document.toString()), metadata)
                );
                assertEquals(test.path("expected").asText(), error.code());
            })
        );
    }

    @Test
    void returnedDocumentsAndSchemasCannotMutateAcceptedState() {
        var document = parser.parse(examples.at("/documents/0").toString());
        String digest = VclCanonical.documentDigest(document);
        ((ObjectNode) document.spec()).put("label", "changed");
        ((ObjectNode) parser.grammar()).put("oneOf", "destroyed");
        assertEquals(digest, VclCanonical.documentDigest(document));
        parser.parse(examples.at("/documents/0").toString());
    }

    @Test
    void canonicalLabelsDoNotChangeSemanticsButDoChangeDocumentDigest() {
        var original = parser.parse(examples.at("/documents/0").toString());
        var renamed = original.json();
        ((ObjectNode) renamed.path("spec")).put("label", "Another label");
        var next = parser.parse(renamed.toString());
        assertEquals(VclCanonical.semanticDigest(original), VclCanonical.semanticDigest(next));
        assertNotEquals(VclCanonical.documentDigest(original), VclCanonical.documentDigest(next));
    }

    @Test
    void registryProjectionsAreCompleteAndClosed() {
        var registry = new VclRegistry(parser);
        assertEquals(21, registry.scalars().size());
        assertEquals(12, registry.operators().size());
        assertEquals(3, registry.scalars().get("inside").arity());
        assertEquals(
            Set.of("sessions", "witnesses"),
            registry.operators().get("absent_within").ports()
        );
        assertFalse(registry.scalars().containsKey("execute_sql"));
        assertEquals(12, registry.capabilities().path("operators").size());
    }

    @Test
    void foreignDatasetAndGrantCannotCompile() {
        VclMetadata denied = new Fixtures() {
            @Override
            public Optional<Snapshot> dataset(UUID id, long revision) {
                return Optional.empty();
            }

            @Override
            public boolean permits(Resource kind, UUID id) {
                return false;
            }
        };
        assertEquals(
            "SCHEMA_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(examples.at("/documents/2").toString()), denied)
            ).code()
        );
        assertEquals(
            "RESOURCE_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(examples.at("/documents/4").toString()), denied)
            ).code()
        );
    }

    @Test
    void dataVersionAndScopeChangesInvalidateStaticCompilation() {
        var document = examples.at("/documents/9").deepCopy();
        ((ObjectNode) document.at("/spec/targetPrecondition")).put("dataVersion", 8);
        assertEquals(
            "TARGET_CONFLICT",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(document.toString()), metadata)
            ).code()
        );
        var count = new java.util.concurrent.atomic.AtomicInteger();
        VclMetadata changing = new Fixtures() {
            @Override
            public String accessScopeDigest() {
                return count.incrementAndGet() == 1 ? "a".repeat(64) : "b".repeat(64);
            }
        };
        assertEquals(
            "ACCESS_SCOPE_CHANGED",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(examples.at("/documents/2").toString()), changing)
            ).code()
        );
    }

    @Test
    void compiledGraphKeepsCanonicalInputsButCannotBeMutatedThroughConfig() {
        var result = compiler.compile(
            parser.parse(examples.at("/documents/4").toString()),
            metadata
        );
        var visit = result
            .graph()
            .stream()
            .filter(n -> n.method().equals("zone_session"))
            .findFirst()
            .orElseThrow();
        assertEquals(Map.of("events", "eligible"), visit.inputs());
        ((ObjectNode) visit.config()).put("exitDebounceUs", "999");
        assertEquals("3000000", visit.config().path("exitDebounceUs").asText());
        assertEquals(registryDigest(), result.registryDigest());
        assertEquals(VclUnits.standard().digest(), result.unitRegistryDigest());
        assertFalse(result.toString().contains("canonicalDocument"));
        assertFalse(visit.toString().contains("exitDebounceUs"));
    }

    private String registryDigest() {
        return new VclRegistry(parser).digest();
    }

    @Test
    void booleanImportTokenSetsCannotOverlap() {
        var document = examples.at("/documents/3").deepCopy();
        var column = (ObjectNode) document.at("/spec/columns/2");
        column.put("sourceType", "TEXT");
        var conversion = column.putArray("conversions").addObject().put("method", "parse_boolean");
        conversion.putArray("trueTokens").add("same");
        conversion.putArray("falseTokens").add("same");
        assertEquals(
            "BOOLEAN_TOKEN_OVERLAP",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(document.toString()), metadata)
            ).code()
        );
    }

    @Test
    void timestampImportWithAnOffsetDoesNotNeedALocalTimezone() {
        var document = examples.at("/documents/3").deepCopy();
        var column = (ObjectNode) document.at("/spec/columns/1");
        column.put("sourceType", "TEXT");
        column
            .putArray("conversions")
            .addObject()
            .put("method", "parse_instant")
            .put("format", "RFC3339_OFFSET");
        VclMetadata timestampTarget = new Fixtures() {
            @Override
            public Optional<Snapshot> dataset(UUID id, long revision) {
                return super.dataset(id, revision).map(snapshot -> {
                    var definition = snapshot.definition().deepCopy();
                    ((ObjectNode) definition.at("/fields/1")).put("type", "INSTANT");
                    return new Snapshot(id, revision, definition);
                });
            }
        };
        compiler.compile(parser.parse(document.toString()), timestampTarget);
    }

    @Test
    void nativeCollectionWithoutBusinessKeyUsesOwnerReservedSystemIdentity() {
        String datasetId = "00000000-0000-4000-8000-000000000005";
        String systemFieldId = "00000000-0000-4000-8000-000000000098";
        VclMetadata unkeyed = new Fixtures() {
            @Override
            public Optional<Snapshot> dataset(UUID id, long revision) {
                var prior = super.dataset(id, revision).orElseThrow();
                var definition = (ObjectNode) prior.definition();
                definition.putArray("keyFieldIds");
                definition.putObject("systemFields").put("recordIdFieldId", systemFieldId);
                return Optional.of(new Snapshot(id, revision, definition));
            }
        };
        var document = examples.at("/documents/9").deepCopy();
        var key = ((ObjectNode) document.path("spec"))
            .putArray("key")
            .addObject()
            .put("fieldId", systemFieldId);
        key.putObject("literal")
            .put("type", "REF")
            .put("value", "00000000-0000-4000-8000-000000000099");
        var result = compiler.compile(parser.parse(document.toString()), unkeyed);
        assertEquals(VclDocument.Kind.MUTATION, result.kind());
        assertTrue(result.canonicalDocument().contains(datasetId));
    }

    private JsonNode patch(JsonNode test) {
        var result = examples.path("documents").get(test.path("documentIndex").asInt()).deepCopy();
        for (var edit : test.path("edits")) {
            String pointer = edit.path("path").asText();
            int split = pointer.lastIndexOf('/');
            var parent = result.at(pointer.substring(0, split));
            String key = pointer
                .substring(split + 1)
                .replace("~1", "/")
                .replace("~0", "~");
            if (parent.isObject()) {
                if (edit.path("op").asText().equals("remove")) ((ObjectNode) parent).remove(key);
                else ((ObjectNode) parent).set(key, edit.path("value").deepCopy());
            } else {
                var array = (ArrayNode) parent;
                int index = Integer.parseInt(key);
                if (index == array.size()) array.add(edit.path("value").deepCopy());
                else array.set(index, edit.path("value").deepCopy());
            }
        }
        return result;
    }

    private JsonNode read(String name) {
        try (var input = getClass().getResourceAsStream("/vcl/" + name)) {
            return mapper.readTree(input);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private class Fixtures implements VclMetadata {

        @Override
        public String accessScopeDigest() {
            return "a".repeat(64);
        }

        @Override
        public Optional<Snapshot> dataset(UUID id, long revision) {
            return find("datasets", id, revision, "revision");
        }

        @Override
        public Optional<Snapshot> binding(UUID id, long revision) {
            return find("bindings", id, revision, "schemaRevision");
        }

        @Override
        public boolean permits(Resource kind, UUID id) {
            return Set.of(99L, 100L, 105L, 106L, 300L, 302L, 501L)
                .stream()
                .map(value ->
                    UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", value))
                )
                .anyMatch(id::equals);
        }

        @Override
        public Optional<String> artifactSha256(UUID id) {
            return java.util.stream.StreamSupport.stream(
                examples.path("fixtureArtifacts").spliterator(),
                false
            )
                .filter(a -> a.path("artifactId").asText().equals(id.toString()))
                .map(a -> VclCanonical.sha256(a.path("contentUtf8").asText()))
                .findFirst();
        }

        private Optional<Snapshot> find(
            String group,
            UUID id,
            long revision,
            String revisionField
        ) {
            return java.util.stream.StreamSupport.stream(
                examples.at("/fixtureRegistry/" + group).spliterator(),
                false
            )
                .filter(
                    d ->
                        d.path("id").asText().equals(id.toString()) &&
                        d.path(revisionField).asLong() == revision
                )
                .map(d -> new Snapshot(id, revision, d))
                .findFirst();
        }
    }
}
