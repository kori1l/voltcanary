package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.port.CapabilityEmbeddings;
import com.voltcanary.aiagent.service.WidgetCatalogueSearch;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WidgetCatalogueSearchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode catalogue() throws Exception {
        return mapper.readTree(
            """
            [{"type":"map","title":"Map","description":"Coordinates and movement","capabilities":{
              "settingContracts":[{"key":"showNotices","type":"BOOLEAN","label":"Show freshness notices","defaultValue":true}],
              "settingsSchema":{"properties":{"showNotices":{"type":["boolean","null"]}}}}},
             {"type":"chart","title":"Chart","description":"Historical trends","capabilities":{"settingContracts":[]}}]
            """
        );
    }

    @Test
    void lexicalResultsIdentifyExactSettingAndLeaveCatalogueUnchanged() throws Exception {
        var catalogue = catalogue();
        var original = catalogue.deepCopy();
        var result = new WidgetCatalogueSearch(mapper, null).search(catalogue, "freshness notices");
        assertEquals("showNotices", result.at("/matches/0/settingKey").asText());
        assertEquals("map", result.at("/matches/0/widgetType").asText());
        assertEquals("LEXICAL_ONLY", result.path("mode").asText());
        assertEquals(0, result.path("embeddingCalls").asInt());
        assertEquals(original, catalogue);
    }

    @Test
    void semanticRankingCachesOnlyCatalogueAndInvalidatesOnContractChange() throws Exception {
        var batches = new ArrayList<List<String>>();
        CapabilityEmbeddings embeddings = new CapabilityEmbeddings() {
            public String identity() {
                return "deterministic-test-space";
            }

            public List<float[]> embed(List<String> texts) {
                batches.add(texts);
                return texts
                    .stream()
                    .map(text ->
                        text.startsWith("chart") || text.equals("opaque query")
                            ? new float[] { 0, 1 }
                            : new float[] { 1, 0 }
                    )
                    .toList();
            }
        };
        var search = new WidgetCatalogueSearch(mapper, embeddings);
        var catalogue = catalogue();
        var first = search.search(catalogue, "opaque query");
        assertEquals("chart", first.at("/matches/0/widgetType").asText());
        assertEquals("HYBRID", first.path("mode").asText());
        assertEquals(2, first.path("embeddingCalls").asInt());
        assertEquals(1, search.search(catalogue, "opaque query").path("embeddingCalls").asInt());
        ((com.fasterxml.jackson.databind.node.ObjectNode) catalogue.get(0)).put(
            "description",
            "Changed contract"
        );
        var changed = search.search(catalogue, "opaque query");
        assertNotEquals(first.path("catalogueDigest"), changed.path("catalogueDigest"));
        assertEquals(2, changed.path("embeddingCalls").asInt());
        assertEquals(List.of(3, 1, 1, 3, 1), batches.stream().map(List::size).toList());
        assertTrue(
            batches
                .getFirst()
                .stream()
                .noneMatch(text -> text.contains("opaque query"))
        );
    }

    @Test
    void invalidVectorsAndUnavailableProvidersRetainLexicalResults() throws Exception {
        for (float[] vector : List.of(
            new float[] { Float.NaN, 1 },
            new float[] { 0, 0 },
            new float[] {}
        )) {
            CapabilityEmbeddings bad = new CapabilityEmbeddings() {
                public String identity() {
                    return "bad-test-space";
                }

                public List<float[]> embed(List<String> texts) {
                    return texts
                        .stream()
                        .map(text -> vector)
                        .toList();
                }
            };
            var result = new WidgetCatalogueSearch(mapper, bad).search(
                catalogue(),
                "freshness notices"
            );
            assertEquals("LEXICAL_ONLY", result.path("mode").asText());
            assertEquals("showNotices", result.at("/matches/0/settingKey").asText());
            assertEquals("EMBEDDINGS_UNAVAILABLE_OR_INVALID", result.path("diagnostic").asText());
        }
        CapabilityEmbeddings unavailable = new CapabilityEmbeddings() {
            public String identity() {
                return "unavailable";
            }

            public List<float[]> embed(List<String> texts) {
                throw new IllegalStateException("private provider error");
            }
        };
        var result = new WidgetCatalogueSearch(mapper, unavailable).search(catalogue(), "notices");
        assertFalse(result.toString().contains("private provider error"));
        assertEquals(1, result.path("embeddingCalls").asInt());
    }

    @Test
    void queryVectorDimensionMismatchCannotUseAnIncompatibleSpace() throws Exception {
        CapabilityEmbeddings mismatch = new CapabilityEmbeddings() {
            public String identity() {
                return "mismatch";
            }

            public List<float[]> embed(List<String> texts) {
                return texts
                    .stream()
                    .map(text -> texts.size() == 1 ? new float[] { 1 } : new float[] { 1, 1 })
                    .toList();
            }
        };
        var result = new WidgetCatalogueSearch(mapper, mismatch).search(catalogue(), "notices");
        assertEquals("LEXICAL_ONLY", result.path("mode").asText());
        assertEquals(2, result.path("embeddingCalls").asInt());
    }

    @Test
    void queryIsRedactedAndNeverStoredInTheSharedCatalogueBatch() throws Exception {
        var batches = new ArrayList<List<String>>();
        CapabilityEmbeddings fixture = new CapabilityEmbeddings() {
            public String identity() {
                return "fixture";
            }

            public List<float[]> embed(List<String> texts) {
                batches.add(texts);
                return texts
                    .stream()
                    .map(text -> new float[] { 1, 1 })
                    .toList();
            }
        };
        var result = new WidgetCatalogueSearch(mapper, fixture).search(
            catalogue(),
            "notices apiKey=secret-value"
        );
        assertFalse(batches.toString().contains("secret-value"));
        assertTrue(batches.get(1).getFirst().contains("[redacted]"));
        assertFalse(result.toString().contains("apiKey"));
    }
}
