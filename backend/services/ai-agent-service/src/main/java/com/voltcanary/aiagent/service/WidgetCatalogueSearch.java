package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.conversation.ConversationRedaction;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.port.CapabilityEmbeddings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/** Advisory search over owner contracts. Complete catalogue and validation remain authoritative. */
@RequiredArgsConstructor
public final class WidgetCatalogueSearch {
    public static final String VERSION = "widget-catalogue-v1";
    private record Document(String type, String setting, String text) { }
    private record Index(List<float[]> vectors, int dimensions) { }
    private record Ranked(Document document, double score) { }
    private final ObjectMapper mapper;
    private final CapabilityEmbeddings embeddings;
    private final Map<String, Index> indexes = new LinkedHashMap<>();

    public String identity() {
        return VERSION + ":" + (embeddings == null ? "lexical" : embeddings.identity());
    }

    public ObjectNode search(JsonNode catalogue, String message) {
        return search(catalogue, message, (purpose, texts) -> embeddings.embed(texts));
    }

    public ObjectNode search(JsonNode catalogue, String message,
            java.util.function.BiFunction<String, List<String>, List<float[]>> dispatch) {
        var result = mapper.createObjectNode().put("version", VERSION).put("advisoryOnly", true);
        String digest = SkillRegistry.digest(identity() + ":" + catalogue);
        result.put("catalogueDigest", digest);
        var matches = result.putArray("matches");
        List<Document> documents = documents(catalogue);
        if (documents.isEmpty()) return result.put("mode", "LEXICAL_ONLY").put("diagnostic", "CATALOGUE_EMPTY_OR_OVERSIZED").put("embeddingCalls", 0);
        String query = ConversationRedaction.text(message == null ? "" : message);
        query = query.substring(0, Math.min(4096, query.length()));
        var terms = terms(query);
        List<float[]> vectors = null;
        float[] queryVector = null;
        int calls = 0;
        String diagnostic = embeddings == null ? "EMBEDDINGS_DISABLED" : "EMPTY_QUERY";
        if (embeddings != null && !terms.isEmpty()) {
            try {
                Index index;
                synchronized (indexes) {
                    index = indexes.get(digest);
                    if (index == null) {
                        calls++;
                        var proposed = dispatch.apply("CATALOGUE_INDEX", documents.stream().map(Document::text).toList());
                        int dimensions = validate(proposed, documents.size(), 0);
                        // Copy arrays so mutable provider buffers cannot change an accepted index.
                        index = new Index(proposed.stream().map(float[]::clone).toList(), dimensions);
                        if (indexes.size() >= 2) indexes.remove(indexes.keySet().iterator().next());
                        indexes.put(digest, index);
                    }
                }
                calls++;
                var queryVectors = dispatch.apply("CATALOGUE_QUERY", List.of(query));
                validate(queryVectors, 1, index.dimensions());
                queryVector = queryVectors.getFirst();
                vectors = index.vectors();
                diagnostic = "EMBEDDINGS_AVAILABLE";
            } catch (RuntimeException unavailable) {
                // Never include provider response bodies, endpoint, credentials or user query in diagnostics.
                diagnostic = "EMBEDDINGS_UNAVAILABLE_OR_INVALID";
            }
        }
        var ranked = new ArrayList<Ranked>();
        for (int i = 0; i < documents.size(); i++) {
            var document = documents.get(i);
            var words = terms(document.text());
            double lexical = terms.isEmpty() ? 0 : (double) terms.stream().filter(words::contains).count() / terms.size();
            double semantic = vectors == null ? 0 : Math.max(0, cosine(queryVector, vectors.get(i)));
            double score = vectors == null ? lexical : 0.45 * lexical + 0.55 * semantic;
            if (score > 0) ranked.add(new Ranked(document, score));
        }
        ranked.stream().sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparing(item -> item.document().type()).thenComparing(item -> item.document().setting()))
                .limit(8).forEach(item -> {
                    var match = matches.addObject().put("widgetType", item.document().type()).put("score", item.score());
                    if (!item.document().setting().isEmpty()) match.put("settingKey", item.document().setting());
                });
        return result.put("mode", vectors == null ? "LEXICAL_ONLY" : "HYBRID")
                .put("diagnostic", diagnostic).put("embeddingCalls", calls);
    }

    public List<float[]> embed(List<String> texts) {
        return embeddings.embed(texts);
    }

    private static List<Document> documents(JsonNode catalogue) {
        if (!catalogue.isArray() || catalogue.size() > 64) return List.of();
        var documents = new ArrayList<Document>();
        for (var widget : catalogue) {
            String type = widget.path("type").asText();
            String purpose = type + " " + widget.path("title").asText() + " " + widget.path("description").asText();
            documents.add(new Document(type, "", bounded(purpose + " " + widget.path("visualizations")
                    + " " + widget.at("/capabilities/dataKind") + " " + widget.at("/capabilities/valueTypes"))));
            for (var setting : widget.at("/capabilities/settingContracts")) {
                String key = setting.path("key").asText();
                documents.add(new Document(type, key, bounded(type + " " + key + " " + setting.path("label").asText()
                        + " " + setting.path("group").asText() + " " + setting.path("type").asText()
                        + " " + setting.path("choices") + " " + widget.at("/capabilities/settingsSchema/properties/" + key))));
            }
            if (documents.size() > 512) return List.of();
        }
        return List.copyOf(documents);
    }

    private static String bounded(String text) {
        return text.substring(0, Math.min(2048, text.length()));
    }

    private static Set<String> terms(String text) {
        // Identifier splitting is lexical indexing, never intent routing or semantic fallback.
        return java.util.Arrays.stream(text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{N}]+"))
                .filter(term -> !term.isBlank()).collect(Collectors.toSet());
    }

    private static int validate(List<float[]> vectors, int count, int expected) {
        if (vectors == null || vectors.size() != count) throw new IllegalArgumentException("Embedding count");
        int dimensions = expected;
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0 || vector.length > 8192) throw new IllegalArgumentException("Embedding dimensions");
            if (dimensions == 0) dimensions = vector.length;
            if (vector.length != dimensions) throw new IllegalArgumentException("Embedding dimensions");
            double norm = 0;
            for (float value : vector) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding value");
                norm += (double) value * value;
            }
            if (norm == 0) throw new IllegalArgumentException("Empty embedding");
        }
        return dimensions;
    }

    private static double cosine(float[] left, float[] right) {
        double product = 0;
        double a = 0;
        double b = 0;
        for (int i = 0; i < left.length; i++) {
            product += (double) left[i] * right[i];
            a += (double) left[i] * left[i];
            b += (double) right[i] * right[i];
        }
        return product / Math.sqrt(a * b);
    }
}
