package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure assignment only: no aggregate, watermark, completeness, state or effects. */
public final class VclWindowPlan {
    public static final String VERSION = "vcl-window-assignment-1";
    private static final long DAY_US = 86_400_000_000L;
    private static final long MAX_SIZE_US = 31 * DAY_US;
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private final String kind;
    private final long sizeUs;
    private final long stepUs;
    private final long alignmentUs;
    private final String calendarUnit;
    private final ZoneId zone;
    private final int weekStartsOn;
    private final String timezoneRulesVersion;
    private final String digest;

    public record Window(Instant startInclusive, Instant endExclusive) {
        public Window {
            micros(startInclusive);
            micros(endExclusive);
            require(startInclusive.isBefore(endExclusive), "WINDOW_RANGE");
        }
        public boolean contains(Instant eventTime) {
            micros(eventTime);
            return !eventTime.isBefore(startInclusive) && eventTime.isBefore(endExclusive);
        }
    }

    private VclWindowPlan(JsonNode window) {
        require(window != null && window.isObject(), "WINDOW_CONFIG_INVALID");
        kind = text(window, "kind");
        if (kind.equals("CALENDAR")) {
            keys(window, Set.of("kind", "unit", "timezone", "weekStartsOn"));
            calendarUnit = text(window, "unit");
            require(Set.of("DAY", "WEEK", "MONTH").contains(calendarUnit), "WINDOW_CONFIG_INVALID");
            require(window.path("weekStartsOn").isIntegralNumber() && window.path("weekStartsOn").canConvertToInt(), "WINDOW_CONFIG_INVALID");
            weekStartsOn = window.path("weekStartsOn").intValue();
            require(weekStartsOn >= 1 && weekStartsOn <= 7, "WINDOW_CONFIG_INVALID");
            String timezone = text(window, "timezone");
            require(timezone.length() <= 80, "WINDOW_CONFIG_INVALID");
            try { zone = ZoneId.of(timezone); }
            catch (DateTimeException error) { throw new VclException("TIMEZONE_INVALID", ""); }
            timezoneRulesVersion = rulesVersion(zone);
            sizeUs = 0;
            stepUs = 0;
            alignmentUs = 0;
        } else {
            require(kind.equals("FIXED") || kind.equals("ROLLING"), "WINDOW_CONFIG_INVALID");
            keys(window, kind.equals("FIXED") ? Set.of("kind", "sizeUs", "alignment") : Set.of("kind", "sizeUs", "stepUs", "alignment"));
            sizeUs = duration(window, "sizeUs");
            require(sizeUs > 0 && sizeUs <= MAX_SIZE_US, "WINDOW_LIMIT");
            stepUs = kind.equals("FIXED") ? sizeUs : duration(window, "stepUs");
            require(stepUs > 0 && stepUs <= sizeUs && 1 + (sizeUs - 1) / stepUs <= 64, "WINDOW_FANOUT_LIMIT");
            try { alignmentUs = micros(Instant.parse(text(window, "alignment"))); }
            catch (DateTimeException error) { throw new VclException("WINDOW_TIME_INVALID", ""); }
            calendarUnit = null;
            zone = null;
            weekStartsOn = 0;
            timezoneRulesVersion = "not-required";
        }
        digest = VclCanonical.sha256(VclCanonical.encode(window) + "\n" + VERSION + "\n" + timezoneRulesVersion);
    }

    public static VclWindowPlan compile(VclCompilation.Node node) {
        require(node != null && "window_aggregate".equals(node.method()), "WINDOW_OPERATOR_REQUIRED");
        return new VclWindowPlan(node.config().path("window"));
    }

    static VclWindowPlan compileIntervalUnion(VclCompilation.Node node) {
        require(node != null && "interval_union_window".equals(node.method()), "WINDOW_OPERATOR_REQUIRED");
        var config = node.config();
        String unit = switch (config.path("window").asText()) {
            case "LOCAL_DAY" -> "DAY";
            case "LOCAL_WEEK" -> "WEEK";
            case "LOCAL_MONTH" -> "MONTH";
            default -> throw new VclException("WINDOW_CONFIG_INVALID", "");
        };
        return new VclWindowPlan(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("kind", "CALENDAR").put("unit", unit).put("timezone", config.path("timezone").textValue())
                .put("weekStartsOn", config.path("weekStartsOn").asInt(1)));
    }

    public String digest() { return digest; }
    public String timezoneRulesVersion() { return timezoneRulesVersion; }
    /** Conservative elapsed bound across the pinned zone rules, including historical offset changes. */
    public long maximumSpanUs() {
        if (zone == null) return sizeUs;
        var rules = zone.getRules();
        var offsets = new ArrayList<ZoneOffset>();
        offsets.add(rules.getOffset(Instant.EPOCH));
        rules.getTransitions().forEach(transition -> {
            offsets.add(transition.getOffsetBefore());
            offsets.add(transition.getOffsetAfter());
        });
        rules.getTransitionRules().forEach(transition -> {
            offsets.add(transition.getOffsetBefore());
            offsets.add(transition.getOffsetAfter());
        });
        var range = offsets.stream().mapToInt(ZoneOffset::getTotalSeconds).summaryStatistics();
        long days = switch (calendarUnit) {
            case "DAY" -> 1;
            case "WEEK" -> 7;
            case "MONTH" -> 31;
            default -> throw new VclException("WINDOW_CONFIG_INVALID", "");
        };
        return days * DAY_US + (range.getMax() - range.getMin()) * 1_000_000L;
    }
    public int maximumFanout() { return zone == null ? (int) (1 + (sizeUs - 1) / stepUs) : 1; }

    public List<Window> assign(Instant eventTime) {
        long eventUs = micros(eventTime);
        if (zone != null) return List.of(calendar(eventTime));
        long startUs = alignmentUs + Math.floorDiv(eventUs - alignmentUs, stepUs) * stepUs;
        var windows = new ArrayList<Window>();
        for (int n = 0; n < maximumFanout() && startUs + sizeUs > eventUs; n++, startUs -= stepUs) {
            windows.add(new Window(instant(startUs), instant(startUs + sizeUs)));
        }
        Collections.reverse(windows);
        return List.copyOf(windows);
    }

    /** Replay must explicitly match the server plan and timezone-rules pins. */
    public List<Window> replay(Instant eventTime, String expectedDigest, String expectedTimezoneRulesVersion) {
        require(digest.equals(expectedDigest), "WINDOW_PLAN_CHANGED");
        require(timezoneRulesVersion.equals(expectedTimezoneRulesVersion), "TIMEZONE_RULES_CHANGED");
        return assign(eventTime);
    }

    private Window calendar(Instant eventTime) {
        require(timezoneRulesVersion.equals(rulesVersion(zone)), "TIMEZONE_RULES_CHANGED");
        try {
            LocalDate date = eventTime.atZone(zone).toLocalDate();
            LocalDate start = switch (calendarUnit) {
                case "DAY" -> date;
                case "WEEK" -> date.minusDays(Math.floorMod(date.getDayOfWeek().getValue() - weekStartsOn, 7));
                case "MONTH" -> date.withDayOfMonth(1);
                default -> throw new VclException("WINDOW_CONFIG_INVALID", "");
            };
            LocalDate end = switch (calendarUnit) {
                case "DAY" -> start.plusDays(1);
                case "WEEK" -> start.plusWeeks(1);
                case "MONTH" -> start.plusMonths(1);
                default -> throw new VclException("WINDOW_CONFIG_INVALID", "");
            };
            var result = new Window(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant());
            require(micros(result.endExclusive()) - micros(result.startInclusive()) <= 32 * DAY_US, "WINDOW_LIMIT");
            require(result.contains(eventTime), "WINDOW_RANGE");
            return result;
        } catch (DateTimeException error) { throw new VclException("WINDOW_RANGE", ""); }
    }

    private static String rulesVersion(ZoneId zone) {
        if (zone.normalized() instanceof ZoneOffset) return "fixed-offset-1";
        var versions = ZoneRulesProvider.getVersions(zone.getId());
        require(!versions.isEmpty(), "TIMEZONE_RULES_UNAVAILABLE");
        String version = versions.lastKey();
        require(versions.get(version).equals(zone.getRules()), "TIMEZONE_RULES_CHANGED");
        return version;
    }

    static long micros(Instant instant) {
        require(instant != null, "WINDOW_TIME_INVALID");
        require(!instant.isBefore(MIN_TIME) && !instant.isAfter(MAX_TIME) && instant.getNano() % 1000 == 0, "WINDOW_TIME_INVALID");
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1000;
    }
    private static Instant instant(long value) {
        var result = Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1000);
        micros(result);
        return result;
    }
    private static long duration(JsonNode node, String name) {
        String value = text(node, name);
        require(value.matches("0|[1-9][0-9]{0,18}"), "WINDOW_CONFIG_INVALID");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new VclException("WINDOW_CONFIG_INVALID", ""); }
    }
    private static String text(JsonNode node, String name) {
        require(node.path(name).isTextual(), "WINDOW_CONFIG_INVALID");
        return node.path(name).textValue();
    }
    private static void keys(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "WINDOW_CONFIG_INVALID");
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
    @Override public String toString() { return "VclWindowPlan[kind=" + kind + ", version=" + VERSION + "]"; }
}
