package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VclBoundaryTest {
    private final VclParser parser = new VclParser();
    private final VclRegistry registry = new VclRegistry(parser);
    private final VclUnits units = VclUnits.standard();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void strictJsonRejectsDuplicatesTrailingDataAndInvalidEncoding() {
        assertCode("INVALID_JSON", () -> parser.parse("{\"language\":\"VCL/1\",\"language\":\"VCL/1\"}"));
        assertCode("INVALID_JSON", () -> parser.parse(example(0).toString() + " {}"));
        assertCode("INVALID_UTF8", () -> parser.parse(new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertCode("INVALID_UNICODE", () -> parser.parse("\"\\ud800\""));
        assertCode("PAYLOAD_SIZE", () -> parser.parse(" ".repeat(VclParser.MAX_BYTES + 1)));
    }

    @Test
    void unsafeNativeJsonNumbersCannotBeRoundedIntoValidVersions() {
        String json = example(0).toString().replace("\"revision\":1", "\"revision\":9007199254740993");
        assertCode("UNSAFE_JSON_NUMBER", () -> parser.parse(json));
        String minimum = example(0).toString().replace("\"revision\":1", "\"revision\":-9223372036854775808");
        assertCode("UNSAFE_JSON_NUMBER", () -> parser.parse(minimum));
    }

    @Test
    void exactTypedNumbersDoNotBecomeJavaScriptDoubles() throws Exception {
        var query = example(2);
        ((ObjectNode) query.path("spec")).set("where", mapper.readTree("""
                {"op":"CALL","method":"eq","args":[
                  {"op":"CONST","literal":{"type":"INT64","value":"9223372036854775807"}},
                  {"op":"CONST","literal":{"type":"INT64","value":"9223372036854775807"}}]}
                """));
        assertTrue(VclCanonical.json(parser.parse(query.toString())).contains("9223372036854775807"));
        var overflow = query.toString().replace("9223372036854775807", "9223372036854775808");
        assertCode("NUMBER_RANGE", () -> VclCanonical.json(parser.parse(overflow)));
    }

    @Test
    void decimalAndUnicodeCanonicalizationAreTypedOnly() throws Exception {
        var query = example(2);
        ((ObjectNode) query.path("spec")).set("where", mapper.readTree("""
                {"op":"CALL","method":"eq","args":[
                  {"op":"CONST","literal":{"type":"DECIMAL","value":"0.2200","unit":"kV"}},
                  {"op":"CONST","literal":{"type":"DECIMAL","value":"0.22","unit":"kV"}}]}
                """));
        String canonical = VclCanonical.json(parser.parse(query.toString()));
        assertTrue(canonical.contains("\"value\":\"0.22\""));
        assertEquals(canonical, VclCanonical.json(parser.parse(query.toString().replace("0.2200", "0.22"))));
        var textQuery = query.toString().replace("\"type\":\"DECIMAL\"", "\"type\":\"TEXT\"").replace(",\"unit\":\"kV\"", "");
        assertNotEquals(VclCanonical.json(parser.parse(textQuery)), VclCanonical.json(parser.parse(textQuery.replace("0.2200", "0.22"))));
    }

    @Test
    void unitsUseExactRationalConversionAndAbsoluteTemperatureRules() {
        assertEquals(0, new BigDecimal("0.22").compareTo(units.convert(new BigDecimal("220"), "V", "kV", MathContext.UNLIMITED)));
        assertEquals(0, new BigDecimal("10").compareTo(units.convert(new BigDecimal("36"), "km/h", "m/s", MathContext.UNLIMITED)));
        assertEquals(0, new BigDecimal("273.15").compareTo(units.convert(BigDecimal.ZERO, "Cel", "K", MathContext.UNLIMITED)));
        assertCode("UNIT_MISMATCH", () -> units.convert(BigDecimal.ONE, "m", "V", MathContext.UNLIMITED));
        assertThrows(ArithmeticException.class, () -> units.convert(BigDecimal.ONE, "km/h", "m/s", MathContext.UNLIMITED));
    }

    @Test
    void expressionDepthAndTotalCountAreIndependentLimits() throws Exception {
        var expressions = types();
        JsonNode expression = mapper.readTree("{\"op\":\"CONST\",\"literal\":{\"type\":\"BOOLEAN\",\"value\":true}}");
        for (int i = 0; i < 16; i++) {
            var next = mapper.createObjectNode().put("op", "CALL").put("method", "not");
            next.putArray("args").add(expression);
            expression = next;
        }
        JsonNode deep = expression;
        assertCode("EXPRESSION_LIMIT", () -> expressions.predicate(deep, Map.of(), "/where"));
        var fresh = types();
        JsonNode literal = mapper.readTree("{\"op\":\"CONST\",\"literal\":{\"type\":\"BOOLEAN\",\"value\":true}}");
        for (int i = 0; i < 128; i++) fresh.predicate(literal, Map.of(), "/where");
        assertCode("EXPRESSION_LIMIT", () -> fresh.predicate(literal, Map.of(), "/where"));
    }

    @Test
    void unknownFieldsAndIncompatibleTypesCannotBecomeFalsePredicates() throws Exception {
        var field = mapper.readTree("{\"op\":\"FIELD\",\"fieldId\":\"00000000-0000-4000-8000-000000000099\"}");
        assertCode("FIELD_NOT_IN_SCOPE", () -> types().predicate(field, Map.of(), "/where"));
        var bad = mapper.readTree("""
                {"op":"CALL","method":"gt","args":[
                 {"op":"CONST","literal":{"type":"DURATION","value":"1000"}},
                 {"op":"CONST","literal":{"type":"DECIMAL","value":"1","unit":"h"}}]}
                """);
        assertCode("TYPE_MISMATCH", () -> types().predicate(bad, Map.of(), "/where"));
    }

    @Test
    void invalidDatesAndIntervalsHaveSafeDiagnostics() throws Exception {
        var literal = mapper.readTree("{\"type\":\"DATE\",\"value\":\"2026-02-30\"}");
        var error = assertThrows(VclException.class, () -> VclValues.normalized(literal));
        assertEquals("DATE_RANGE", error.code());
        assertEquals("DATE_RANGE at ", error.getMessage());
        var interval = mapper.readTree("{\"type\":\"INTERVAL\",\"value\":{\"start\":\"2026-11-03T10:00:00Z\",\"end\":\"2026-11-03T09:00:00Z\"}}");
        assertCode("INTERVAL_REVERSED", () -> VclValues.normalized(interval));
    }

    @Test
    void renamedEnumLiteralCannotSilentlyFilterEverythingOut() throws Exception {
        var expression = mapper.readTree("""
                {"op":"CALL","method":"eq","args":[
                 {"op":"FIELD","fieldId":"status"},
                 {"op":"CONST","literal":{"type":"ENUM","value":"MISSPELLED"}}]}
                """);
        var type = new VclType(VclType.Kind.ENUM, false, "1", null, java.util.Set.of("WORK", "BREAK"));
        assertCode("ENUM_VALUE_INVALID", () -> types().predicate(expression, Map.of("status", type), "/where"));
    }

    @Test
    void textCanonicalizationMustNotCollapseDifferentExternalSourceKeys() throws Exception {
        var source = mapper.readTree("""
                {"targetPrecondition":{"mode":"EXTERNAL"},
                 "key":[{"literal":{"type":"TEXT","value":"e\\u0301"}}],
                 "values":[{"literal":{"type":"TEXT","value":"e\\u0301"}}]}
                """);
        var normalized = VclValues.normalized(source);
        assertEquals("e\u0301", normalized.at("/key/0/literal/value").asText());
        assertEquals("\u00e9", normalized.at("/values/0/literal/value").asText());
        ((ObjectNode) source.path("targetPrecondition")).put("mode", "NATIVE");
        assertEquals("\u00e9", VclValues.normalized(source).at("/key/0/literal/value").asText());
    }

    @Test
    void uuidIdentityNormalizationDoesNotChangeTextLookalikes() throws Exception {
        var value = mapper.readTree("""
                {"datasetId":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                 "literal":{"type":"TEXT","value":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}}
                """);
        var normalized = VclValues.normalized(value);
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", normalized.path("datasetId").asText());
        assertEquals("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", normalized.at("/literal/value").asText());
    }

    private VclExpressionTypes types() {
        return new VclExpressionTypes(registry, units, id -> { throw new VclException("RESOURCE_UNAVAILABLE", "/literal"); });
    }

    private JsonNode example(int i) {
        try (var input = getClass().getResourceAsStream("/vcl/configurable-operations-language.examples.json")) {
            return mapper.readTree(input).path("documents").get(i).deepCopy();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(VclException.class, action).code());
    }
}
