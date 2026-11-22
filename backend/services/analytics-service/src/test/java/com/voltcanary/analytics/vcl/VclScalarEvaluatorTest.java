package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VclScalarEvaluatorTest {

    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();
    private static final VclScalarCompiler COMPILER = new VclScalarCompiler(
        PARSER,
        REGISTRY,
        UNITS
    );
    private static final VclScalarEvaluator EVALUATOR = new VclScalarEvaluator(
        UNITS,
        REGISTRY.digest()
    );
    private static final String SCOPE = "a".repeat(64);
    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final ScalarDecimalPolicy REJECT = new ScalarDecimalPolicy(
        38,
        12,
        ScalarDecimalPolicy.Rounding.REJECT
    );
    private static final VclMetadata METADATA = new VclMetadata() {
        public String accessScopeDigest() {
            return SCOPE;
        }

        public Optional<Snapshot> dataset(UUID id, long revision) {
            return Optional.empty();
        }

        public Optional<Snapshot> binding(UUID id, long revision) {
            return Optional.empty();
        }

        public boolean permits(Resource kind, UUID id) {
            return false;
        }
    };

    @ParameterizedTest
    @CsvSource({
        "and,T,T,T",
        "and,T,F,F",
        "and,T,U,U",
        "and,F,T,F",
        "and,F,F,F",
        "and,F,U,F",
        "and,U,T,U",
        "and,U,F,F",
        "and,U,U,U",
        "or,T,T,T",
        "or,T,F,T",
        "or,T,U,T",
        "or,F,T,T",
        "or,F,F,F",
        "or,F,U,U",
        "or,U,T,T",
        "or,U,F,U",
        "or,U,U,U",
    })
    void completeKleeneTables(String method, String a, String b, String expected) {
        var program = compile(
            call(method, field(A), field(B)),
            Map.of(A, VclType.BOOLEAN, B, VclType.BOOLEAN)
        );
        var result = EVALUATOR.evaluate(program, Map.of(A, truth(a), B, truth(b)), REJECT, SCOPE);
        assertEquals(
            expected,
            result.status() == ScalarValue.Status.UNKNOWN
                ? "U"
                : result.literal().path("value").asBoolean()
                  ? "T"
                  : "F"
        );
    }

    @ParameterizedTest
    @CsvSource({ "T,F", "F,T", "U,U" })
    void completeKleeneNegation(String input, String expected) {
        var program = compile(call("not", field(A)), Map.of(A, VclType.BOOLEAN));
        var result = EVALUATOR.evaluate(program, Map.of(A, truth(input)), REJECT, SCOPE);
        assertEquals(
            expected,
            result.status() == ScalarValue.Status.UNKNOWN
                ? "U"
                : result.literal().path("value").asBoolean()
                  ? "T"
                  : "F"
        );
    }

    @ParameterizedTest
    @CsvSource({
        "eq,1,1,true",
        "ne,1,2,true",
        "lt,1,2,true",
        "le,2,2,true",
        "gt,2,1,true",
        "ge,2,2,true",
        "eq,1,2,false",
    })
    void exactComparisons(String method, String a, String b, boolean expected) {
        assertEquals(
            expected,
            evaluate(call(method, constant("DECIMAL", a), constant("DECIMAL", b)))
                .literal()
                .path("value")
                .asBoolean()
        );
    }

    @Test
    void diagnosticsDistinguishKnownNullUnknownAndFailures() {
        var nullable = new VclType(VclType.Kind.BOOLEAN, true, "1", null);
        var unknown = ScalarValue.unknown("COVERAGE_MISSING", List.of(A));
        var nullValue = known("{\"type\":\"NULL\",\"value\":null}");
        for (String method : List.of("is_known", "is_null")) {
            var program = compile(call(method, field(A)), Map.of(A, nullable));
            assertTrue(
                EVALUATOR.evaluate(program, Map.of(A, nullValue), REJECT, SCOPE)
                    .literal()
                    .path("value")
                    .asBoolean()
            );
            var result = EVALUATOR.evaluate(program, Map.of(A, unknown), REJECT, SCOPE);
            if (method.equals("is_known")) assertFalse(result.literal().path("value").asBoolean());
            else assertEquals(List.of(A), result.evidence());
            var denied = ScalarValue.failure(ScalarValue.Status.ACCESS_REVOKED, "ACCESS_REVOKED");
            assertEquals(denied, EVALUATOR.evaluate(program, Map.of(A, denied), REJECT, SCOPE));
        }
        var eq = compile(call("eq", field(A), field(A)), Map.of(A, nullable));
        assertEquals(
            "NULL_OPERAND",
            EVALUATOR.evaluate(eq, Map.of(A, nullValue), REJECT, SCOPE).reason()
        );
        var not = compile(call("not", field(A)), Map.of(A, nullable));
        assertEquals(
            "COVERAGE_MISSING",
            EVALUATOR.evaluate(not, Map.of(A, unknown), REJECT, SCOPE).reason()
        );
        assertEquals(
            "NULL_NOT_ALLOWED",
            EVALUATOR.evaluate(
                compile(field(A), Map.of(A, VclType.BOOLEAN)),
                Map.of(A, nullValue),
                REJECT,
                SCOPE
            ).reason()
        );
    }

    @Test
    void errorsCannotBeSuppressedByBooleanLogic() {
        for (String method : List.of("and", "or")) {
            var program = compile(
                call(method, field(A), field(B)),
                Map.of(A, VclType.BOOLEAN, B, VclType.BOOLEAN)
            );
            var result = EVALUATOR.evaluate(
                program,
                Map.of(
                    A,
                    truth(method.equals("and") ? "F" : "T"),
                    B,
                    ScalarValue.failure(ScalarValue.Status.OPERATOR_ERROR, "BROKEN_INPUT")
                ),
                REJECT,
                SCOPE
            );
            assertEquals(ScalarValue.Status.OPERATOR_ERROR, result.status());
        }
    }

    @Test
    void missingFieldRetainsEvidenceAndKnownFieldsAreTypeChecked() {
        var program = compile(
            call("eq", field(A), constant("INT64", "1")),
            Map.of(A, new VclType(VclType.Kind.INT64, false, "1", null))
        );
        assertEquals(List.of(A), EVALUATOR.evaluate(program, Map.of(), REJECT, SCOPE).evidence());
        assertEquals(
            ScalarValue.Status.INVALID_INPUT,
            EVALUATOR.evaluate(
                program,
                Map.of(A, known(literal("TEXT", "1"))),
                REJECT,
                SCOPE
            ).status()
        );
    }

    @Test
    void integerArithmeticNeverUsesDoubleOrWraps() {
        assertValue(
            "9223372036854775807",
            evaluate(call("add", constant("INT64", "9223372036854775806"), constant("INT64", "1")))
        );
        assertValue(
            "-9223372036854775808",
            evaluate(
                call("subtract", constant("INT64", "-9223372036854775807"), constant("INT64", "1"))
            )
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(
                call("add", constant("INT64", "9223372036854775807"), constant("INT64", "1"))
            ).status()
        );
    }

    @Test
    void decimalPolicyRejectsLossAndRoundsOnlyWhenPinned() {
        assertValue(
            "0.3",
            evaluate(call("add", constant("DECIMAL", "0.1"), constant("DECIMAL", "0.2")))
        );
        assertValue(
            "-0.1",
            evaluate(call("subtract", constant("DECIMAL", "0.1"), constant("DECIMAL", "0.2")))
        );
        assertValue(
            "0.02",
            evaluate(call("multiply", constant("DECIMAL", "0.1"), constant("DECIMAL", "0.2")))
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(call("divide", constant("DECIMAL", "1"), constant("DECIMAL", "3"))).status()
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(call("divide", constant("DECIMAL", "1"), constant("DECIMAL", "0"))).status()
        );
        var third = compile(
            call("divide", constant("DECIMAL", "1"), constant("DECIMAL", "3")),
            Map.of()
        );
        assertValue(
            "0.33",
            EVALUATOR.evaluate(
                third,
                Map.of(),
                new ScalarDecimalPolicy(38, 2, ScalarDecimalPolicy.Rounding.HALF_EVEN),
                SCOPE
            )
        );
        var tie = compile(
            call("divide", constant("DECIMAL", "1"), constant("DECIMAL", "8")),
            Map.of()
        );
        assertValue(
            "0.12",
            EVALUATOR.evaluate(
                tie,
                Map.of(),
                new ScalarDecimalPolicy(38, 2, ScalarDecimalPolicy.Rounding.HALF_EVEN),
                SCOPE
            )
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(
                call(
                    "multiply",
                    constant("DECIMAL", "99999999999999999999999999999999999999"),
                    constant("DECIMAL", "2")
                )
            ).status()
        );
    }

    @Test
    void unitConversionsStayExactUntilOutputPolicy() {
        assertValue(
            "10",
            evaluate(call("convert_unit", unitConstant("36", "km/h"), constant("TEXT", "m/s")))
        );
        assertValue(
            "273.15",
            evaluate(call("convert_unit", unitConstant("0", "Cel"), constant("TEXT", "K")))
        );
        assertValue(
            "0.22",
            evaluate(call("convert_unit", unitConstant("220", "V"), constant("TEXT", "kV")))
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(
                call("convert_unit", unitConstant("1", "km/h"), constant("TEXT", "m/s"))
            ).status()
        );
        assertThrows(VclException.class, () ->
            compile(call("add", unitConstant("1", "Cel"), unitConstant("2", "Cel")), Map.of())
        );
    }

    @Test
    void durationConversionIsCheckedMicroseconds() {
        assertValue(
            "5400000000",
            evaluate(call("duration_from", constant("DECIMAL", "1.5"), constant("TEXT", "h")))
        );
        assertValue(
            "1",
            evaluate(call("duration_from", constant("DECIMAL", "0.000001"), constant("TEXT", "s")))
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(
                call("duration_from", constant("DECIMAL", "0.1"), constant("TEXT", "us"))
            ).status()
        );
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            evaluate(
                call(
                    "duration_from",
                    constant("DECIMAL", "9223372036854775807"),
                    constant("TEXT", "h")
                )
            ).status()
        );
    }

    @Test
    void textAndTimeUseCanonicalTypedValues() {
        assertTrue(
            evaluate(call("text_eq", constant("TEXT", "e\u0301"), constant("TEXT", "é")))
                .literal()
                .path("value")
                .asBoolean()
        );
        assertTrue(
            evaluate(call("text_prefix", constant("TEXT", "Door OPEN"), constant("TEXT", "Door")))
                .literal()
                .path("value")
                .asBoolean()
        );
        assertFalse(
            evaluate(call("text_eq", constant("TEXT", "OPEN"), constant("TEXT", "open")))
                .literal()
                .path("value")
                .asBoolean()
        );
        assertTrue(
            evaluate(
                call(
                    "eq",
                    constant("INSTANT", "2026-11-03T11:00:00.000000Z"),
                    constant("INSTANT", "2026-11-03T11:00:00Z")
                )
            )
                .literal()
                .path("value")
                .asBoolean()
        );
        assertThrows(VclException.class, () ->
            evaluate(constant("INSTANT", "2026-11-03T12:00:00+01:00"))
        );
        assertTrue(
            evaluate(call("lt", constant("DATE", "2026-10-31"), constant("DATE", "2026-11-03")))
                .literal()
                .path("value")
                .asBoolean()
        );
    }

    @Test
    void scopeAndVersionMismatchFailClosedBeforeExecution() {
        var program = compile(constant("INT64", "1"), Map.of());
        assertEquals(
            ScalarValue.Status.ACCESS_REVOKED,
            EVALUATOR.evaluate(program, Map.of(), REJECT, "b".repeat(64)).status()
        );
        assertEquals(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            new VclScalarEvaluator(UNITS, "wrong")
                .evaluate(program, Map.of(), REJECT, SCOPE)
                .status()
        );
    }

    @Test
    void referenceLiteralsRequireOwnerAuthorizationBeforeIrConstruction() {
        assertEquals(
            "RESOURCE_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                compile(constant("REF", A.toString()), Map.of())
            ).code()
        );
        assertEquals(
            "FIELD_NOT_IN_SCOPE",
            assertThrows(VclException.class, () -> compile(field(A), Map.of())).code()
        );
    }

    @Test
    void unknownEvidenceIsMergedAndBounded() {
        var program = compile(
            call("and", field(A), field(B)),
            Map.of(A, VclType.BOOLEAN, B, VclType.BOOLEAN)
        );
        var result = EVALUATOR.evaluate(
            program,
            Map.of(
                A,
                ScalarValue.unknown("MISSING_A", List.of(A)),
                B,
                ScalarValue.unknown("MISSING_B", List.of(B))
            ),
            REJECT,
            SCOPE
        );
        assertEquals(List.of(A, B), result.evidence());
        var evidence = java.util.stream.LongStream.range(0, 128)
            .mapToObj(i -> new UUID(0, i))
            .toList();
        assertEquals(
            ScalarValue.Status.STATE_LIMIT,
            EVALUATOR.evaluate(
                program,
                Map.of(
                    A,
                    ScalarValue.unknown("MISSING_A", evidence),
                    B,
                    ScalarValue.unknown("MISSING_B", List.of(B))
                ),
                REJECT,
                SCOPE
            ).status()
        );
    }

    @Test
    void typedEnumAndUnitFieldsCannotBeRelabeledAtRuntime() {
        var enumeration = new VclType(
            VclType.Kind.ENUM,
            false,
            "1",
            null,
            java.util.Set.of("OPEN", "CLOSED")
        );
        var program = compile(field(A), Map.of(A, enumeration));
        assertEquals(
            ScalarValue.Status.INVALID_INPUT,
            EVALUATOR.evaluate(
                program,
                Map.of(A, known(literal("ENUM", "OTHER"))),
                REJECT,
                SCOPE
            ).status()
        );
        var metres = compile(
            field(A),
            Map.of(A, new VclType(VclType.Kind.DECIMAL, false, "m", null))
        );
        assertEquals(
            "UNIT_MISMATCH",
            EVALUATOR.evaluate(
                metres,
                Map.of(A, known(literal("DECIMAL", "1"))),
                REJECT,
                SCOPE
            ).reason()
        );
    }

    @Test
    void malformedAndOversizedExpressionsNeverProduceExecutableIr() {
        for (String expression : List.of(
            "{\"op\":\"FIELD\",\"fieldId\":\"" + A + "\",\"sql\":\"select 1\"}",
            "{\"op\":\"CALL\",\"method\":\"eval\",\"args\":[]}",
            "{\"op\":\"CONST\",\"op\":\"CONST\",\"literal\":{\"type\":\"BOOLEAN\",\"value\":true}}",
            call("not", constant("TEXT", "true")),
            call("add", constant("INT64", "1"))
        )) {
            assertThrows(VclException.class, () -> compile(expression, Map.of()));
        }
        String expression = constant("BOOLEAN", true);
        for (int i = 0; i < 16; i++) expression = call("not", expression);
        String tooDeep = expression;
        assertEquals(
            "EXPRESSION_LIMIT",
            assertThrows(VclException.class, () -> compile(tooDeep, Map.of())).code()
        );
    }

    @Test
    void resultAndProgramSnapshotsCannotBeMutated() {
        var program = compile(constant("TEXT", "private"), Map.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode) program.root().literal()).put(
            "value",
            "changed"
        );
        var result = EVALUATOR.evaluate(program, Map.of(), REJECT, SCOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.literal()).put("value", "changed");
        assertValue("private", result);
        assertFalse(result.toString().contains("private"));
    }

    @Test
    void distanceUsesRegisteredWgs84Kernel() {
        var point =
            "{\"op\":\"CONST\",\"literal\":{\"type\":\"POINT\",\"value\":{\"longitude\":24.9,\"latitude\":60.1}}}";
        assertValue("0", evaluate(call("distance_m", point, point)));
    }

    private static VclScalarProgram compile(String json, Map<UUID, VclType> fields) {
        return COMPILER.compile(json, fields, METADATA);
    }

    private static ScalarValue evaluate(String expression) {
        return EVALUATOR.evaluate(compile(expression, Map.of()), Map.of(), REJECT, SCOPE);
    }

    private static ScalarValue known(String literal) {
        return ScalarValue.known(PARSER, literal);
    }

    private static ScalarValue truth(String value) {
        return value.equals("U")
            ? ScalarValue.unknown("COVERAGE_MISSING", List.of(A))
            : known(literal("BOOLEAN", value.equals("T")));
    }

    private static String field(UUID id) {
        return "{\"op\":\"FIELD\",\"fieldId\":\"" + id + "\"}";
    }

    private static String literal(String type, Object value) {
        return new ObjectMapper()
            .createObjectNode()
            .put("type", type)
            .set("value", new ObjectMapper().valueToTree(value))
            .toString();
    }

    private static String constant(String type, Object value) {
        return "{\"op\":\"CONST\",\"literal\":" + literal(type, value) + "}";
    }

    private static String unitConstant(String value, String unit) {
        return (
            "{\"op\":\"CONST\",\"literal\":{\"type\":\"DECIMAL\",\"value\":\"" +
            value +
            "\",\"unit\":\"" +
            unit +
            "\"}}"
        );
    }

    private static String call(String method, String... args) {
        return (
            "{\"op\":\"CALL\",\"method\":\"" +
            method +
            "\",\"args\":[" +
            String.join(",", args) +
            "]}"
        );
    }

    private static void assertValue(String expected, ScalarValue result) {
        assertEquals(ScalarValue.Status.OK, result.status(), result.reason());
        assertEquals(expected, result.literal().path("value").asText());
    }
}
