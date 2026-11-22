package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VclWindowPlanTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
        "1969-12-31T23:59:59.999999Z,1969-12-31T23:59:50Z,1970-01-01T00:00:00Z",
        "1970-01-01T00:00:00Z,1970-01-01T00:00:00Z,1970-01-01T00:00:10Z",
        "1970-01-01T00:00:09.999999Z,1970-01-01T00:00:00Z,1970-01-01T00:00:10Z",
        "1970-01-01T00:00:10Z,1970-01-01T00:00:10Z,1970-01-01T00:00:20Z",
    })
    void fixedWindowsAreExactHalfOpenAcrossEpoch(String event, String start, String end) {
        var plan = elapsed("FIXED", "10000000", null, "1970-01-01T00:00:00Z");
        assertEquals(List.of(window(start, end)), plan.assign(Instant.parse(event)));
        assertEquals(1, plan.maximumFanout());
    }

    @Test
    void microsecondAlignmentNeverRoundsToMilliseconds() {
        var plan = elapsed("FIXED", "3", null, "1970-01-01T00:00:00.000001Z");
        assertEquals(
            List.of(window("1969-12-31T23:59:59.999998Z", "1970-01-01T00:00:00.000001Z")),
            plan.assign(Instant.EPOCH)
        );
        assertEquals(
            List.of(window("1970-01-01T00:00:00.000001Z", "1970-01-01T00:00:00.000004Z")),
            plan.assign(microInstant(1))
        );
    }

    @Test
    void rollingMembershipMatchesIndependentBruteForceIncludingNonDivisibleSteps() {
        for (int size = 1; size <= 17; size++) {
            for (int step = 1; step <= size; step++) {
                var plan = elapsed("ROLLING", "" + size, "" + step, "1970-01-01T00:00:00.000002Z");
                for (int event = -25; event <= 25; event++) {
                    var expected = new ArrayList<VclWindowPlan.Window>();
                    for (int grid = -60; grid <= 60; grid++) {
                        long start = 2L + grid * step;
                        if (start <= event && event < start + size) expected.add(
                            new VclWindowPlan.Window(
                                microInstant(start),
                                microInstant(start + size)
                            )
                        );
                    }
                    var actual = plan.assign(microInstant(event));
                    assertEquals(
                        expected,
                        actual,
                        "size=" + size + ", step=" + step + ", event=" + event
                    );
                    assertTrue(actual.size() <= plan.maximumFanout());
                }
            }
        }
    }

    @Test
    void fanoutIsBoundedBeforeAnyPublication() {
        var plan = elapsed("ROLLING", "64", "1", "1970-01-01T00:00:00Z");
        assertEquals(64, plan.maximumFanout());
        assertEquals(64, plan.assign(Instant.EPOCH).size());
        assertThrows(VclException.class, () ->
            elapsed("ROLLING", "65", "1", "1970-01-01T00:00:00Z")
        );
        assertEquals(64, elapsed("ROLLING", "127", "2", "1970-01-01T00:00:00Z").maximumFanout());
        assertThrows(VclException.class, () ->
            elapsed("ROLLING", "129", "2", "1970-01-01T00:00:00Z")
        );
    }

    @ParameterizedTest
    @CsvSource({
        "Europe/Belgrade,DAY,2026-03-29T12:00:00Z,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z,23",
        "Europe/Belgrade,DAY,2026-10-25T12:00:00Z,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z,25",
        "Europe/Belgrade,MONTH,2026-10-15T12:00:00Z,2026-09-30T22:00:00Z,2026-10-31T23:00:00Z,745",
        "Europe/Belgrade,MONTH,2026-03-15T12:00:00Z,2026-02-28T23:00:00Z,2026-03-31T22:00:00Z,743",
        "UTC,MONTH,2024-02-29T23:59:59.999999Z,2024-02-01T00:00:00Z,2024-03-01T00:00:00Z,696",
        "America/Sao_Paulo,DAY,2018-11-04T12:00:00Z,2018-11-04T03:00:00Z,2018-11-05T02:00:00Z,23",
        "Pacific/Apia,DAY,2011-12-29T12:00:00Z,2011-12-29T10:00:00Z,2011-12-30T10:00:00Z,24",
    })
    void calendarsUseActualLocalBoundariesAndElapsedDuration(
        String zone,
        String unit,
        String event,
        String start,
        String end,
        long hours
    ) {
        var plan = calendar(unit, zone, 1);
        var result = plan.assign(Instant.parse(event));
        assertEquals(List.of(window(start, end)), result);
        assertEquals(
            hours,
            Duration.between(
                result.getFirst().startInclusive(),
                result.getFirst().endExclusive()
            ).toHours()
        );
        assertEquals(1, plan.maximumFanout());
        assertTrue(result.getFirst().contains(Instant.parse(event)));
        assertFalse(result.getFirst().contains(Instant.parse(end)));
    }

    @Test
    void repeatedLocalHoursBelongToSameDayAndHalfHourTransitionsArePreserved() {
        var day = calendar("DAY", "Europe/Belgrade", 1);
        assertEquals(
            day.assign(Instant.parse("2026-10-25T00:30:00Z")),
            day.assign(Instant.parse("2026-10-25T01:30:00Z"))
        );
        var lordHowe = calendar("DAY", "Australia/Lord_Howe", 1)
            .assign(Instant.parse("2026-04-05T00:00:00Z"))
            .getFirst();
        assertEquals(
            1470,
            Duration.between(lordHowe.startInclusive(), lordHowe.endExclusive()).toMinutes()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "1,2026-11-02T00:00:00Z",
        "2,2026-10-27T00:00:00Z",
        "3,2026-10-28T00:00:00Z",
        "4,2026-10-29T00:00:00Z",
        "5,2026-10-30T00:00:00Z",
        "6,2026-10-31T00:00:00Z",
        "7,2026-11-01T00:00:00Z",
    })
    void weekStartUsesIsoDayNumber(int weekStartsOn, String start) {
        var result = calendar("WEEK", "UTC", weekStartsOn)
            .assign(Instant.parse("2026-11-02T12:00:00Z"))
            .getFirst();
        assertEquals(Instant.parse(start), result.startInclusive());
        assertEquals(
            Duration.ofDays(7),
            Duration.between(result.startInclusive(), result.endExclusive())
        );
    }

    @Test
    void exactCalendarEndEntersNextWindowAndSkippedDateDoesNotInventAnEmptyWindow() {
        var month = calendar("MONTH", "Europe/Belgrade", 1);
        var first = month.assign(Instant.parse("2026-10-31T22:59:59.999999Z")).getFirst();
        assertEquals(
            first.endExclusive(),
            month.assign(first.endExclusive()).getFirst().startInclusive()
        );
        var apia = calendar("DAY", "Pacific/Apia", 1);
        var before = apia.assign(Instant.parse("2011-12-30T09:59:59.999999Z")).getFirst();
        var after = apia.assign(before.endExclusive()).getFirst();
        assertEquals(before.endExclusive(), after.startInclusive());
        assertEquals(Instant.parse("2011-12-31T10:00:00Z"), after.endExclusive());
    }

    @Test
    void versionedPlansAreImmutableAndReplayRequiresBothPins() throws Exception {
        var config = JSON.readTree(
            "{\"kind\":\"CALENDAR\",\"unit\":\"MONTH\",\"timezone\":\"Europe/Belgrade\",\"weekStartsOn\":1}"
        );
        var node = node(config);
        var plan = VclWindowPlan.compile(node);
        String digest = plan.digest();
        ((ObjectNode) config).put("timezone", "UTC");
        ((ObjectNode) node.config().path("window")).put("unit", "DAY");
        assertEquals(digest, plan.digest());
        assertEquals(digest, VclWindowPlan.compile(node).digest());
        assertNotEquals("not-required", plan.timezoneRulesVersion());
        assertNotEquals("fixed-offset-1", plan.timezoneRulesVersion());
        var event = Instant.parse("2026-10-15T12:00:00Z");
        assertEquals(plan.assign(event), plan.replay(event, digest, plan.timezoneRulesVersion()));
        assertThrows(VclException.class, () ->
            plan.replay(event, "a".repeat(64), plan.timezoneRulesVersion())
        );
        assertThrows(VclException.class, () -> plan.replay(event, digest, "old-tzdb"));
        assertThrows(UnsupportedOperationException.class, () -> plan.assign(event).clear());
        assertNotEquals(digest, calendar("MONTH", "UTC", 1).digest());
        assertEquals("fixed-offset-1", calendar("DAY", "+03:00", 1).timezoneRulesVersion());
        assertFalse(plan.toString().contains("Europe/Belgrade"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "0000-12-31T23:59:59Z",
            "+10000-01-01T00:00:00Z",
            "1970-01-01T00:00:00.000000001Z",
        }
    )
    void outOfRangeAndSubMicrosecondEventsFailWithoutClipping(String time) {
        var plan = elapsed("FIXED", "1", null, "1970-01-01T00:00:00Z");
        assertThrows(VclException.class, () -> plan.assign(Instant.parse(time)));
    }

    @Test
    void extremeDatesUseExactArithmeticAndBoundariesCannotEscapeSupportedRange() {
        var micro = elapsed("FIXED", "1", null, "0001-01-01T00:00:00Z");
        assertEquals(
            Instant.parse("9999-12-31T23:59:59.999999Z"),
            micro.assign(Instant.parse("9999-12-31T23:59:59.999998Z")).getFirst().endExclusive()
        );
        assertThrows(VclException.class, () ->
            micro.assign(Instant.parse("9999-12-31T23:59:59.999999Z"))
        );
        assertThrows(VclException.class, () -> micro.assign(Instant.MIN));
        assertThrows(VclException.class, () -> micro.assign(null));
        assertThrows(VclException.class, () ->
            calendar("MONTH", "UTC", 1).assign(Instant.parse("9999-12-01T00:00:00Z"))
        );
        assertEquals(
            Instant.parse("0001-01-01T00:00:00Z"),
            calendar("DAY", "UTC", 1)
                .assign(Instant.parse("0001-01-01T12:00:00Z"))
                .getFirst()
                .startInclusive()
        );
        assertThrows(VclException.class, () ->
            new VclWindowPlan.Window(Instant.EPOCH, Instant.EPOCH)
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "null",
            "[]",
            "{\"kind\":\"FIXED\",\"sizeUs\":\"0\",\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"FIXED\",\"sizeUs\":\"2678400000001\",\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"FIXED\",\"sizeUs\":1,\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"FIXED\",\"sizeUs\":\"01\",\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"FIXED\",\"sizeUs\":\"9223372036854775808\",\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"FIXED\",\"sizeUs\":\"1\",\"alignment\":\"1970-01-01T00:00:00.000000001Z\"}",
            "{\"kind\":\"FIXED\",\"sizeUs\":\"1\",\"alignment\":\"yesterday\"}",
            "{\"kind\":\"ROLLING\",\"sizeUs\":\"1\",\"stepUs\":\"0\",\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"ROLLING\",\"sizeUs\":\"1\",\"stepUs\":\"2\",\"alignment\":\"1970-01-01T00:00:00Z\"}",
            "{\"kind\":\"CALENDAR\",\"unit\":\"YEAR\",\"timezone\":\"UTC\",\"weekStartsOn\":1}",
            "{\"kind\":\"CALENDAR\",\"unit\":\"DAY\",\"timezone\":\"No/SuchZone\",\"weekStartsOn\":1}",
            "{\"kind\":\"CALENDAR\",\"unit\":\"DAY\",\"timezone\":\"UTC\",\"weekStartsOn\":0}",
            "{\"kind\":\"CALENDAR\",\"unit\":\"DAY\",\"timezone\":\"UTC\",\"weekStartsOn\":1.0}",
            "{\"kind\":\"CALENDAR\",\"unit\":\"DAY\",\"timezone\":\"UTC\",\"weekStartsOn\":1,\"complete\":true}",
        }
    )
    void malformedAndAuthorityExpandingWindowDeclarationsAreRejected(
        String config
    ) throws Exception {
        var value = JSON.readTree(config);
        assertThrows(VclException.class, () -> VclWindowPlan.compile(node(value)));
    }

    @Test
    void onlyWindowAggregateNodeCanProducePlan() {
        var node = new VclCompilation.Node(
            "x",
            "read_stream",
            Map.of(),
            JSON.createObjectNode(),
            "EVENT",
            Map.of(),
            null
        );
        assertThrows(VclException.class, () -> VclWindowPlan.compile(node));
        assertThrows(VclException.class, () -> VclWindowPlan.compile(null));
        assertEquals(
            1,
            elapsed("FIXED", "2678400000000", null, "1970-01-01T00:00:00Z").maximumFanout()
        );
    }

    private static VclWindowPlan elapsed(String kind, String size, String step, String alignment) {
        var value = JSON.createObjectNode()
            .put("kind", kind)
            .put("sizeUs", size)
            .put("alignment", alignment);
        if (step != null) value.put("stepUs", step);
        return VclWindowPlan.compile(node(value));
    }

    private static VclWindowPlan calendar(String unit, String zone, int weekStartsOn) {
        return VclWindowPlan.compile(
            node(
                JSON.createObjectNode()
                    .put("kind", "CALENDAR")
                    .put("unit", unit)
                    .put("timezone", zone)
                    .put("weekStartsOn", weekStartsOn)
            )
        );
    }

    private static VclCompilation.Node node(JsonNode window) {
        var config = JSON.createObjectNode();
        config.set("window", window);
        return new VclCompilation.Node(
            "aggregate",
            "window_aggregate",
            Map.of("events", "source"),
            config,
            "MEASURE",
            Map.of(),
            null
        );
    }

    private static VclWindowPlan.Window window(String start, String end) {
        return new VclWindowPlan.Window(Instant.parse(start), Instant.parse(end));
    }

    private static Instant microInstant(long value) {
        return Instant.EPOCH.plusNanos(value * 1000);
    }
}
