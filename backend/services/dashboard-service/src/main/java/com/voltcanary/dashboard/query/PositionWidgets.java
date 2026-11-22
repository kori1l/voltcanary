package com.voltcanary.dashboard.query;

import java.util.Set;

/** Widgets backed by the authorized source-bound position evidence contract. */
public final class PositionWidgets {

    private static final Set<String> TYPES = Set.of(
        "vehicle-map",
        "vehicle-value",
        "vehicle-timeline"
    );

    private PositionWidgets() {}

    public static boolean contains(String type) {
        return TYPES.contains(type);
    }
}
