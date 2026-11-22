package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VclSpatialKernelTest {

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
    private static final ScalarDecimalPolicy POLICY = new ScalarDecimalPolicy(
        38,
        12,
        ScalarDecimalPolicy.Rounding.REJECT
    );
    private static final String SCOPE = "a".repeat(64);
    private static final UUID FEATURE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID POSITION = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ACCURACY = UUID.fromString("10000000-0000-0000-0000-000000000003");
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
            return kind == Resource.REFERENCE && id.equals(FEATURE);
        }

        public Optional<VclType> referenceType(UUID id) {
            return id.equals(FEATURE)
                ? Optional.of(new VclType(VclType.Kind.REF, false, "1", "RESOURCE:SPATIAL_FEATURE"))
                : Optional.empty();
        }
    };

    // Reference distances: analytic equator/meridian cases and independent published
    // GeographicLib regression vectors (MIT), not this kernel's Direct/Inverse output.
    // https://github.com/geographiclib/geographiclib-java/blob/main/src/test/java/net/sf/geographiclib/GeodesicTest.java
    @ParameterizedTest
    @CsvSource({
        "0,0,0,0,0",
        "0,0,1,0,111319.49079327357",
        "0,0,0,1,110574.38855779878",
        "0,0,180,0,20003931.458625447",
        "179,0,-179,0,222638.98158654713",
        "0,90,120,90,0",
        "0,90,0,-90,20003931.458625447",
        "-139.44815,35.60777,-69.95921,-11.17491,8935244.5604818305",
        "142.59065,-21.97856,98.56635,41.84138,8394328.894657671",
        "173.34268,-17.42761,5.93557,-15.84784,16076603.1631180673",
        "-16.30485,-50.56724,-94.97412,-33.56571,6455670.5118668696",
        "85.66836,-87.85331,16.09921,66.48646,17286615.3147144645",
    })
    void referenceDistancesMeetDeclaredKernelBound(
        double lon1,
        double lat1,
        double lon2,
        double lat2,
        String expected
    ) {
        var a = new SpatialFeatureSnapshot.Point(lon1, lat1);
        var b = new SpatialFeatureSnapshot.Point(lon2, lat2);
        var actual = Wgs84ScalarKernel.distanceMetres(a, b);
        assertTrue(
            actual
                .subtract(new BigDecimal(expected))
                .abs()
                .compareTo(Wgs84ScalarKernel.ERROR_BOUND_METRES) <= 0
        );
        assertEquals(actual, Wgs84ScalarKernel.distanceMetres(b, a));
    }

    @Test
    void coordinateOrderIsLongitudeThenLatitudeAndPointInputsAreStrict() {
        assertThrows(IllegalArgumentException.class, () ->
            new SpatialFeatureSnapshot.Point(Double.NaN, 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SpatialFeatureSnapshot.Point(0, Double.POSITIVE_INFINITY)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SpatialFeatureSnapshot.Point(180.1, 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SpatialFeatureSnapshot.Point(0, -90.1)
        );
        assertNotEquals(
            Wgs84ScalarKernel.distanceMetres(
                new SpatialFeatureSnapshot.Point(0, 0),
                new SpatialFeatureSnapshot.Point(1, 0)
            ),
            Wgs84ScalarKernel.distanceMetres(
                new SpatialFeatureSnapshot.Point(0, 0),
                new SpatialFeatureSnapshot.Point(0, 1)
            )
        );
    }

    @ParameterizedTest
    @CsvSource({ "24,T", "29,U", "36,F", "40,F" })
    void approvedThirtyMetreVisitFixture(String distance, String expected) {
        // WGS84 equatorial distance is a * longitudeRadians. This fixture does not use Direct.
        double longitude = Math.toDegrees(Double.parseDouble(distance) / 6_378_137D);
        var snapshot = snapshot(1, "30");
        var result = evaluate(
            inside(point(longitude, 0), decimal("5")),
            Map.of(),
            context(snapshot, Set.of(FEATURE))
        );
        assertEquals(expected, truth(result));
        if (expected.equals("U")) assertEquals(List.of(FEATURE), result.evidence());
    }

    @Test
    void errorBoundIsIncludedAndBoundaryTestsAreConservative() {
        var centre = point(0, 0);
        assertEquals(
            "U",
            truth(
                evaluate(
                    inside(centre, decimal("1")),
                    Map.of(),
                    context(snapshot(1, "1"), Set.of(FEATURE))
                )
            )
        );
        assertEquals(
            "T",
            truth(
                evaluate(
                    inside(centre, decimal("1")),
                    Map.of(),
                    context(snapshot(1, "1.000001"), Set.of(FEATURE))
                )
            )
        );
        assertEquals(
            "T",
            truth(
                evaluate(
                    inside(centre, decimal("0")),
                    Map.of(),
                    context(snapshot(1, "30"), Set.of(FEATURE))
                )
            )
        );
        double lon = Math.toDegrees(40D / 6_378_137D);
        assertEquals(
            "U",
            truth(
                evaluate(
                    inside(point(lon, 0), decimal("9.999999")),
                    Map.of(),
                    context(snapshot(1, "30"), Set.of(FEATURE))
                )
            )
        );
        assertEquals(
            "F",
            truth(
                evaluate(
                    inside(point(lon, 0), decimal("9.999998")),
                    Map.of(),
                    context(snapshot(1, "30"), Set.of(FEATURE))
                )
            )
        );
    }

    @Test
    void unknownOrNullAccuracyNeverBecomesZeroAndNegativeAccuracyIsInvalid() {
        var expression = inside(point(0, 0), field(ACCURACY));
        var unknown = ScalarValue.unknown("ACCURACY_MISSING", List.of(ACCURACY));
        var context = context(snapshot(1, "30"), Set.of(FEATURE));
        assertEquals(
            "ACCURACY_MISSING",
            evaluate(expression, Map.of(ACCURACY, unknown), context).reason()
        );
        var nullValue = ScalarValue.known(PARSER, "{\"type\":\"NULL\",\"value\":null}");
        assertEquals(
            ScalarValue.Status.UNKNOWN,
            evaluate(expression, Map.of(ACCURACY, nullValue), context).status()
        );
        assertEquals(
            "ACCURACY_NEGATIVE",
            evaluate(inside(point(0, 0), decimal("-1")), Map.of(), context).reason()
        );
        assertEquals(
            "ACCURACY_NEGATIVE",
            evaluate(inside(field(POSITION), decimal("-1")), Map.of(), context).reason()
        );
    }

    @Test
    void revokedReferenceAndMissingRevisionCannotBeHiddenByUnknownOrIsKnown() {
        var full = snapshot(1, "30");
        String expression = call("is_known", inside(field(POSITION), decimal("5")));
        assertEquals(
            ScalarValue.Status.ACCESS_REVOKED,
            evaluate(expression, Map.of(), context(full, Set.of())).status()
        );
        var empty = new SpatialFeatureSnapshot(SCOPE, Map.of());
        assertEquals(
            "SPATIAL_REVISION_UNAVAILABLE",
            evaluate(expression, Map.of(), context(empty, Set.of(FEATURE))).reason()
        );
        assertEquals(
            ScalarValue.Status.ACCESS_REVOKED,
            evaluate(
                call("and", bool(false), inside(point(0, 0), decimal("5"))),
                Map.of(),
                context(full, Set.of())
            ).status()
        );
    }

    @Test
    void kernelScopeAndSnapshotPinsFailClosed() {
        var full = snapshot(1, "30");
        var expression = inside(point(0, 0), decimal("5"));
        assertEquals(
            "SPATIAL_KERNEL_UNAVAILABLE",
            evaluate(
                expression,
                Map.of(),
                new SpatialEvaluationContext("unavailable", full.digest(), full, Set.of(FEATURE))
            ).reason()
        );
        var changed = snapshot(2, "31");
        assertEquals(
            "SPATIAL_SNAPSHOT_CHANGED",
            evaluate(
                expression,
                Map.of(),
                new SpatialEvaluationContext(
                    Wgs84ScalarKernel.VERSION,
                    full.digest(),
                    changed,
                    Set.of(FEATURE)
                )
            ).reason()
        );
        var wrongScope = new SpatialFeatureSnapshot("b".repeat(64), full.circles());
        assertEquals(
            ScalarValue.Status.ACCESS_REVOKED,
            evaluate(expression, Map.of(), context(wrongScope, Set.of(FEATURE))).status()
        );
    }

    @Test
    void oldPinnedGeometryReplaysWithoutUsingLatestRevision() {
        double longitude = Math.toDegrees(40D / 6_378_137D);
        var expression = inside(point(longitude, 0), decimal("5"));
        var old = snapshot(1, "30");
        var revised = snapshot(2, "50");
        assertEquals("F", truth(evaluate(expression, Map.of(), context(old, Set.of(FEATURE)))));
        assertEquals("T", truth(evaluate(expression, Map.of(), context(revised, Set.of(FEATURE)))));
        assertEquals("F", truth(evaluate(expression, Map.of(), context(old, Set.of(FEATURE)))));
        assertNotEquals(old.digest(), revised.digest());
    }

    @Test
    void snapshotsAreCanonicalImmutableBoundedAndDoNotPrintGeometry() {
        var full = snapshot(1, "30.0");
        assertEquals(full.digest(), snapshot(1, "30").digest());
        var copy = new LinkedHashMap<>(full.circles());
        var stable = new SpatialFeatureSnapshot(SCOPE, copy);
        copy.clear();
        assertEquals(full.digest(), stable.digest());
        assertThrows(UnsupportedOperationException.class, () -> full.circles().clear());
        assertFalse(full.toString().contains("longitude"));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, "30"));
        assertThrows(IllegalArgumentException.class, () -> snapshot(1, "0"));
        assertThrows(IllegalArgumentException.class, () -> snapshot(1, "20100001"));
        assertThrows(IllegalArgumentException.class, () ->
            new SpatialFeatureSnapshot(SCOPE, Map.of(POSITION, full.circles().get(FEATURE)))
        );
        var huge = new LinkedHashMap<UUID, SpatialFeatureSnapshot.Circle>();
        for (int i = 0; i < 129; i++) {
            var id = new UUID(0, i);
            huge.put(
                id,
                new SpatialFeatureSnapshot.Circle(
                    id,
                    1,
                    new SpatialFeatureSnapshot.Point(0, 0),
                    BigDecimal.ONE
                )
            );
        }
        assertThrows(IllegalArgumentException.class, () -> new SpatialFeatureSnapshot(SCOPE, huge));
    }

    @Test
    void distanceOutputUsesNanometresThenExplicitDecimalPolicy() {
        var expression = call("distance_m", point(0, 0), point(1, 0));
        var program = compile(expression);
        var result = EVALUATOR.evaluate(program, Map.of(), POLICY, SCOPE);
        assertEquals("111319.490793274", result.literal().path("value").asText());
        assertEquals(
            ScalarValue.Status.OPERATOR_ERROR,
            EVALUATOR.evaluate(
                program,
                Map.of(),
                new ScalarDecimalPolicy(38, 2, ScalarDecimalPolicy.Rounding.REJECT),
                SCOPE
            ).status()
        );
        assertEquals(
            "111319.49",
            EVALUATOR.evaluate(
                program,
                Map.of(),
                new ScalarDecimalPolicy(38, 2, ScalarDecimalPolicy.Rounding.HALF_EVEN),
                SCOPE
            )
                .literal()
                .path("value")
                .asText()
        );
    }

    private static SpatialFeatureSnapshot snapshot(long revision, String radius) {
        return new SpatialFeatureSnapshot(
            SCOPE,
            Map.of(
                FEATURE,
                new SpatialFeatureSnapshot.Circle(
                    FEATURE,
                    revision,
                    new SpatialFeatureSnapshot.Point(0, 0),
                    new BigDecimal(radius)
                )
            )
        );
    }

    private static SpatialEvaluationContext context(
        SpatialFeatureSnapshot snapshot,
        Set<UUID> ids
    ) {
        return new SpatialEvaluationContext(
            Wgs84ScalarKernel.VERSION,
            snapshot.digest(),
            snapshot,
            ids
        );
    }

    private static VclScalarProgram compile(String expression) {
        return COMPILER.compile(
            expression,
            Map.of(
                POSITION,
                new VclType(VclType.Kind.POINT, true, "1", null),
                ACCURACY,
                new VclType(VclType.Kind.DECIMAL, true, "m", null)
            ),
            METADATA
        );
    }

    private static ScalarValue evaluate(
        String expression,
        Map<UUID, ScalarValue> record,
        SpatialEvaluationContext context
    ) {
        return EVALUATOR.evaluate(compile(expression), record, POLICY, SCOPE, context);
    }

    private static String truth(ScalarValue result) {
        if (result.status() == ScalarValue.Status.UNKNOWN) return "U";
        assertEquals(ScalarValue.Status.OK, result.status(), result.reason());
        return result.literal().path("value").asBoolean() ? "T" : "F";
    }

    private static String point(double longitude, double latitude) {
        return (
            "{\"op\":\"CONST\",\"literal\":{\"type\":\"POINT\",\"value\":{\"longitude\":" +
            longitude +
            ",\"latitude\":" +
            latitude +
            "}}}"
        );
    }

    private static String decimal(String value) {
        return (
            "{\"op\":\"CONST\",\"literal\":{\"type\":\"DECIMAL\",\"value\":\"" +
            value +
            "\",\"unit\":\"m\"}}"
        );
    }

    private static String bool(boolean value) {
        return "{\"op\":\"CONST\",\"literal\":{\"type\":\"BOOLEAN\",\"value\":" + value + "}}";
    }

    private static String field(UUID id) {
        return "{\"op\":\"FIELD\",\"fieldId\":\"" + id + "\"}";
    }

    private static String inside(String point, String accuracy) {
        return call(
            "inside",
            point,
            "{\"op\":\"CONST\",\"literal\":{\"type\":\"REF\",\"value\":\"" + FEATURE + "\"}}",
            accuracy
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
}
