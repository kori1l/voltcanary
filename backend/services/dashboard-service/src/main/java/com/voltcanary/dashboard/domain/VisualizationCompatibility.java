package com.voltcanary.dashboard.domain;

import java.util.List;

public final class VisualizationCompatibility {

    private VisualizationCompatibility() {}

    public static List<String> choices(String type) {
        return switch (type) {
            case "fleet-count", "quality-and-freshness" -> List.of(
                "number",
                "bar",
                "horizontal-bar",
                "stacked-bar",
                "pie",
                "donut"
            );
            case "time-series" -> List.of("line", "area", "bar", "horizontal-bar");
            case "vehicle-timeline" -> List.of("list", "line");
            case "energy-period" -> List.of("number", "bar", "horizontal-bar", "pie", "donut");
            case "latest-value", "metric-summary", "estimated-cost", "uptime-or-cycle" -> List.of(
                "number"
            );
            default -> List.of();
        };
    }
}
