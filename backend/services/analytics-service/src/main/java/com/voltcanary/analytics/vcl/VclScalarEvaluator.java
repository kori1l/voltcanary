package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Pure bounded scalar execution. No Spring beans, owner I/O or temporal state. */
@RequiredArgsConstructor
public final class VclScalarEvaluator {

    private static final String SUPPORTED_REGISTRY_DIGEST = new com.voltcanary.vcl.VclRegistry(
        new com.voltcanary.vcl.VclParser()
    ).digest();
    private final VclUnits units;
    private final String registryDigest;

    public ScalarValue evaluate(
        VclScalarProgram program,
        Map<UUID, ScalarValue> record,
        ScalarDecimalPolicy policy,
        String authorizedScopeDigest
    ) {
        if (!program.scopeDigest().equals(authorizedScopeDigest)) return failure(
            ScalarValue.Status.ACCESS_REVOKED,
            "ACCESS_SCOPE_CHANGED"
        );
        return evaluate(
            program,
            record,
            policy,
            authorizedScopeDigest,
            SpatialEvaluationContext.distanceOnly(authorizedScopeDigest)
        );
    }

    public ScalarValue evaluate(
        VclScalarProgram program,
        Map<UUID, ScalarValue> record,
        ScalarDecimalPolicy policy,
        String authorizedScopeDigest,
        SpatialEvaluationContext spatial
    ) {
        if (!program.scopeDigest().equals(authorizedScopeDigest)) return failure(
            ScalarValue.Status.ACCESS_REVOKED,
            "ACCESS_SCOPE_CHANGED"
        );
        if (
            !program.unitDigest().equals(units.digest()) ||
            !program.registryDigest().equals(registryDigest) ||
            !SUPPORTED_REGISTRY_DIGEST.equals(registryDigest)
        ) {
            return failure(ScalarValue.Status.SCHEMA_UNAVAILABLE, "REGISTRY_VERSION_UNAVAILABLE");
        }
        if (spatial == null) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "SPATIAL_CONTEXT_REQUIRED"
        );
        if (!spatial.snapshot().scopeDigest().equals(authorizedScopeDigest)) return failure(
            ScalarValue.Status.ACCESS_REVOKED,
            "SPATIAL_SCOPE_CHANGED"
        );
        if (!Wgs84ScalarKernel.VERSION.equals(spatial.kernelVersion())) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "SPATIAL_KERNEL_UNAVAILABLE"
        );
        if (!spatial.expectedSnapshotDigest().equals(spatial.snapshot().digest())) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "SPATIAL_SNAPSHOT_CHANGED"
        );
        // Copy the owner snapshot once. Unknown/failed fields are data, not omitted map entries.
        var snapshot = new java.util.LinkedHashMap<UUID, ScalarValue>();
        for (var fieldId : program.fields().keySet()) {
            var value = record.get(fieldId);
            if (value != null) snapshot.put(fieldId, value);
        }
        return evaluate(program.root(), snapshot, policy, spatial);
    }

    private ScalarValue evaluate(
        VclScalarProgram.Node node,
        Map<UUID, ScalarValue> record,
        ScalarDecimalPolicy policy,
        SpatialEvaluationContext spatial
    ) {
        if (node.operation().equals("CONST")) return ScalarValue.known(node.literal());
        if (node.operation().equals("FIELD")) return field(node, record);
        var args = new ArrayList<ScalarValue>();
        for (var child : node.arguments()) args.add(evaluate(child, record, policy, spatial));
        // Infrastructure/input/operator failures cannot be hidden by Boolean annihilators or diagnostics.
        for (var value : args) if (value.isFailure()) return value;
        String method = node.operation();
        ScalarValue a = args.getFirst();
        if (method.equals("inside")) {
            var readiness = spatialReadiness(args, spatial);
            if (readiness != null) return readiness;
        }
        if (method.equals("is_known")) return truth(a.status() == ScalarValue.Status.OK);
        if (method.equals("is_null")) return a.status() == ScalarValue.Status.UNKNOWN
            ? a
            : truth(a.isNull());
        if (method.equals("and") || method.equals("or")) return logic(method, a, args.get(1));
        if (method.equals("not")) return knownTruth(a) ? truth(!booleanValue(a)) : unknown(args);
        if (
            args
                .stream()
                .anyMatch(value -> value.status() == ScalarValue.Status.UNKNOWN || value.isNull())
        ) return unknown(args);
        try {
            return call(node, args, policy, spatial);
        } catch (ArithmeticException error) {
            return failure(ScalarValue.Status.OPERATOR_ERROR, "ARITHMETIC_ERROR");
        } catch (VclException error) {
            return failure(ScalarValue.Status.OPERATOR_ERROR, error.code());
        }
    }

    private ScalarValue field(VclScalarProgram.Node node, Map<UUID, ScalarValue> record) {
        var value = record.get(node.fieldId());
        if (value == null) return ScalarValue.unknown("FIELD_MISSING", List.of(node.fieldId()));
        if (value.status() != ScalarValue.Status.OK) return value;
        if (value.isNull()) return node.type().nullable()
            ? value
            : failure(ScalarValue.Status.INVALID_INPUT, "NULL_NOT_ALLOWED");
        var actual = VclType.from(value.literal());
        try {
            node.type().compatible(actual, "");
            if (
                actual.kind() == VclType.Kind.ENUM &&
                !node.type().enumValues().contains(value.literal().path("value").asText())
            ) {
                return failure(ScalarValue.Status.INVALID_INPUT, "ENUM_VALUE_INVALID");
            }
            return value;
        } catch (VclException error) {
            return failure(ScalarValue.Status.INVALID_INPUT, error.code());
        }
    }

    private ScalarValue call(
        VclScalarProgram.Node node,
        List<ScalarValue> args,
        ScalarDecimalPolicy policy,
        SpatialEvaluationContext spatial
    ) {
        var a = args.getFirst().literal().path("value");
        var b = args.size() > 1 ? args.get(1).literal().path("value") : null;
        return switch (node.operation()) {
            case "eq", "ne", "lt", "le", "gt", "ge" -> {
                int comparison = compare(node.arguments().getFirst().type(), a, b);
                yield truth(
                    switch (node.operation()) {
                        case "eq" -> comparison == 0;
                        case "ne" -> comparison != 0;
                        case "lt" -> comparison < 0;
                        case "le" -> comparison <= 0;
                        case "gt" -> comparison > 0;
                        default -> comparison >= 0;
                    }
                );
            }
            case "text_eq" -> truth(a.asText().equals(b.asText()));
            case "text_prefix" -> truth(a.asText().startsWith(b.asText()));
            case "add", "subtract" -> {
                boolean add = node.operation().equals("add");
                if (node.type().kind() == VclType.Kind.INT64) {
                    long result = add
                        ? Math.addExact(Long.parseLong(a.asText()), Long.parseLong(b.asText()))
                        : Math.subtractExact(
                              Long.parseLong(a.asText()),
                              Long.parseLong(b.asText())
                          );
                    yield number(node.type(), Long.toString(result));
                }
                var result = add ? decimal(a).add(decimal(b)) : decimal(a).subtract(decimal(b));
                yield number(node.type(), policy.apply(result).toPlainString());
            }
            case "multiply" -> number(
                node.type(),
                policy.apply(decimal(a).multiply(decimal(b))).toPlainString()
            );
            case "divide" -> number(
                node.type(),
                policy
                    .apply(decimal(a).divide(decimal(b), policy.scale(), policy.mode()))
                    .toPlainString()
            );
            case "convert_unit" -> {
                // Exact rational conversion until the single explicit output-scale rounding.
                var source = units.require(node.arguments().getFirst().type().unit(), "");
                var target = units.require(b.asText(), "");
                var numerator = decimal(a)
                    .multiply(source.factor())
                    .add(source.offset().subtract(target.offset()).multiply(source.divisor()))
                    .multiply(target.divisor());
                var result = numerator.divide(
                    source.divisor().multiply(target.factor()),
                    policy.scale(),
                    policy.mode()
                );
                yield number(node.type(), policy.apply(result).toPlainString());
            }
            case "duration_from" -> {
                long factor = switch (b.asText()) {
                    case "us" -> 1L;
                    case "ms" -> 1_000L;
                    case "s" -> 1_000_000L;
                    case "min" -> 60_000_000L;
                    case "h" -> 3_600_000_000L;
                    default -> throw new ArithmeticException("Unregistered duration unit");
                };
                yield number(
                    node.type(),
                    Long.toString(decimal(a).multiply(BigDecimal.valueOf(factor)).longValueExact())
                );
            }
            case "distance_m" -> number(
                node.type(),
                policy.apply(Wgs84ScalarKernel.distanceMetres(point(a), point(b))).toPlainString()
            );
            case "inside" -> inside(
                point(a),
                UUID.fromString(b.asText()),
                decimal(args.get(2).literal().path("value")),
                spatial
            );
            default -> failure(ScalarValue.Status.OPERATOR_ERROR, "METHOD_UNKNOWN");
        };
    }

    private static SpatialFeatureSnapshot.Point point(JsonNode value) {
        return new SpatialFeatureSnapshot.Point(
            value.path("longitude").doubleValue(),
            value.path("latitude").doubleValue()
        );
    }

    private static ScalarValue inside(
        SpatialFeatureSnapshot.Point point,
        UUID id,
        BigDecimal accuracy,
        SpatialEvaluationContext spatial
    ) {
        var feature = spatial.snapshot().circles().get(id);
        return switch (Wgs84ScalarKernel.classify(point, feature, accuracy)) {
            case INSIDE -> truth(true);
            case OUTSIDE -> truth(false);
            case UNCERTAIN -> ScalarValue.unknown("SPATIAL_BOUNDARY_UNCERTAIN", List.of(id));
        };
    }

    private static ScalarValue spatialReadiness(
        List<ScalarValue> args,
        SpatialEvaluationContext spatial
    ) {
        var reference = args.get(1);
        if (reference.status() == ScalarValue.Status.OK && !reference.isNull()) {
            var id = UUID.fromString(reference.literal().path("value").asText());
            if (!spatial.authorizedFeatureIds().contains(id)) return failure(
                ScalarValue.Status.ACCESS_REVOKED,
                "SPATIAL_ACCESS_REVOKED"
            );
            if (!spatial.snapshot().circles().containsKey(id)) return failure(
                ScalarValue.Status.SCHEMA_UNAVAILABLE,
                "SPATIAL_REVISION_UNAVAILABLE"
            );
        }
        var accuracy = args.get(2);
        if (
            accuracy.status() == ScalarValue.Status.OK &&
            !accuracy.isNull() &&
            decimal(accuracy.literal().path("value")).signum() < 0
        ) {
            return failure(ScalarValue.Status.INVALID_INPUT, "ACCURACY_NEGATIVE");
        }
        return null;
    }

    private static int compare(VclType type, JsonNode a, JsonNode b) {
        return switch (type.kind()) {
            case INT64, DECIMAL, DURATION -> decimal(a).compareTo(decimal(b));
            case DATE -> LocalDate.parse(a.asText()).compareTo(LocalDate.parse(b.asText()));
            case INSTANT -> OffsetDateTime.parse(a.asText())
                .toInstant()
                .compareTo(OffsetDateTime.parse(b.asText()).toInstant());
            case TEXT -> compareCodePoints(a.asText(), b.asText());
            case ENUM, REF -> a.asText().compareTo(b.asText());
            case BOOLEAN -> Boolean.compare(a.booleanValue(), b.booleanValue());
            case POINT -> decimal(a.path("longitude")).compareTo(decimal(b.path("longitude"))) ==
                0 &&
            decimal(a.path("latitude")).compareTo(decimal(b.path("latitude"))) == 0
                ? 0
                : 1;
            case INTERVAL -> a.equals(b) ? 0 : 1;
            default -> throw new ArithmeticException("Unsupported comparison");
        };
    }

    private static int compareCodePoints(String a, String b) {
        var left = a.codePoints().iterator();
        var right = b.codePoints().iterator();
        while (left.hasNext() && right.hasNext()) {
            int comparison = Integer.compare(left.nextInt(), right.nextInt());
            if (comparison != 0) return comparison;
        }
        return Boolean.compare(left.hasNext(), right.hasNext());
    }

    private static ScalarValue logic(String method, ScalarValue a, ScalarValue b) {
        boolean and = method.equals("and");
        if (
            (knownTruth(a) && booleanValue(a) != and) || (knownTruth(b) && booleanValue(b) != and)
        ) return truth(!and);
        if (knownTruth(a) && knownTruth(b)) return truth(and);
        return unknown(List.of(a, b));
    }

    private static ScalarValue unknown(List<ScalarValue> args) {
        var evidence = new java.util.LinkedHashSet<UUID>();
        String reason = "NULL_OPERAND";
        for (var value : args) {
            if (value.status() == ScalarValue.Status.UNKNOWN) {
                if (reason.equals("NULL_OPERAND")) reason = value.reason();
                evidence.addAll(value.evidence());
            }
        }
        if (evidence.size() > 128) return failure(ScalarValue.Status.STATE_LIMIT, "EVIDENCE_LIMIT");
        return ScalarValue.unknown(reason, List.copyOf(evidence));
    }

    private static boolean knownTruth(ScalarValue value) {
        return value.status() == ScalarValue.Status.OK && !value.isNull();
    }

    private static boolean booleanValue(ScalarValue value) {
        return value.literal().path("value").booleanValue();
    }

    private static BigDecimal decimal(JsonNode value) {
        return new BigDecimal(value.asText());
    }

    private static ScalarValue truth(boolean value) {
        return ScalarValue.known(
            JsonNodeFactory.instance.objectNode().put("type", "BOOLEAN").put("value", value)
        );
    }

    private static ScalarValue number(VclType type, String value) {
        var literal = JsonNodeFactory.instance
            .objectNode()
            .put("type", type.kind().name())
            .put("value", value);
        if (type.numeric() && !type.unit().equals("1")) literal.put("unit", type.unit());
        return ScalarValue.known(literal);
    }

    private static ScalarValue failure(ScalarValue.Status status, String reason) {
        return ScalarValue.failure(status, reason);
    }
}
