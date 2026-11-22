package com.voltcanary.common.events.telemetry;

import java.util.Map;
import java.util.Set;

/** Observed journey attributes, separate from numeric metric measurements. */
public record TransportContext(
    String routeId,
    String routeShortName,
    Integer direction,
    String destination,
    String nextStopId,
    Integer scheduleDeviationSeconds,
    Boolean doorsOpen,
    Double accelerationMs2,
    String scheduledStart,
    String locationSource
) {
    private static final Set<String> FIELDS = Set.of(
        "routeId",
        "routeShortName",
        "direction",
        "destination",
        "nextStopId",
        "scheduleDeviationSeconds",
        "doorsOpen",
        "accelerationMs2",
        "scheduledStart",
        "locationSource"
    );

    public static boolean validMetrics(Map<String, Object> metrics) {
        if (metrics == null || !metrics.containsKey("transport")) return true;
        if (
            !GpsMetrics.hasPosition(metrics) ||
            !(metrics.get("transport") instanceof Map<?, ?> values) ||
            !FIELDS.containsAll(values.keySet())
        ) return false;
        return (
            text(values.get("routeId"), 128) &&
            text(values.get("routeShortName"), 64) &&
            text(values.get("destination"), 200) &&
            text(values.get("nextStopId"), 128) &&
            number(values.get("direction"), 1, 2, true) &&
            number(values.get("scheduleDeviationSeconds"), -86400, 86400, true) &&
            number(values.get("accelerationMs2"), -30, 30, false) &&
            (values.get("doorsOpen") == null || values.get("doorsOpen") instanceof Boolean) &&
            (values.get("scheduledStart") == null ||
                (values.get("scheduledStart") instanceof String time &&
                    time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))) &&
            (values.get("locationSource") == null ||
                Set.of("GPS", "ODO", "MAN", "DR", "N/A").contains(values.get("locationSource")))
        );
    }

    public static TransportContext fromMetrics(Map<String, Object> metrics) {
        if (metrics == null || !(metrics.get("transport") instanceof Map<?, ?> values)) return null;
        if (!validMetrics(metrics)) throw new IllegalArgumentException("Invalid transport context");
        return new TransportContext(
            (String) values.get("routeId"),
            (String) values.get("routeShortName"),
            integer(values.get("direction")),
            (String) values.get("destination"),
            (String) values.get("nextStopId"),
            integer(values.get("scheduleDeviationSeconds")),
            (Boolean) values.get("doorsOpen"),
            values.get("accelerationMs2") == null
                ? null
                : ((Number) values.get("accelerationMs2")).doubleValue(),
            (String) values.get("scheduledStart"),
            (String) values.get("locationSource")
        );
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static boolean text(Object value, int maximum) {
        return (
            value == null ||
            (value instanceof String text &&
                !text.isBlank() &&
                text.length() <= maximum &&
                text.codePoints().noneMatch(Character::isISOControl))
        );
    }

    private static boolean number(Object value, double minimum, double maximum, boolean integer) {
        return (
            value == null ||
            (value instanceof Number number &&
                Double.isFinite(number.doubleValue()) &&
                number.doubleValue() >= minimum &&
                number.doubleValue() <= maximum &&
                (!integer || number.doubleValue() % 1 == 0))
        );
    }
}
