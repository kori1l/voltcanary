package com.voltcanary.aiagent.service;

/** General design decisions from actual data; no device-specific dashboard recipes. */
public final class DashboardDesignGuide {
    public static final String VERSION = "dashboard-design-v2";

    private DashboardDesignGuide() { }

    public static String text() {
        return VERSION + "\n" + """
                Responsibility: select content and hierarchy from actual purpose, data choices and capabilities. No mandatory widget bundle exists. Planner routes workflows/resources; this skill owns domain choices.
                User control: requested metrics, roles, views, text, periods, order and size are hard constraints. Design only unspecified aspects. Delegated design means useful selection, not every available metric/view. Do not add unrelated metrics or silently replace requested roles. Ask only for material ambiguity/conflict, not delegated choices. Disclose unsupported views and offer supported alternatives.
                Read valueType, unit, format, family, CURRENT/HISTORY views, settings and freshness. Identifiers are not continuous measurements; strings may be text/categories/timestamps. Preserve exact strings, leading zeros and declared time/zone semantics. false, zero and empty strings are not null. Never invent observations or describe missing/stale data as fresh. Requested label/footer visibility changes do not change freshness or supply missing readings.
                General examples: a measurement's current value and trend need separate show_current/show_history roles when both are requested. Text/identifier history uses an offered list, not an invented numeric line. Boolean/category transitions use offered state/history views. Timestamp formatting must be supported by the renderer.
                Design a clear primary signal, group related data and use consistent units. History/maps/long text may need more space; current cards may share a row. Use bounded place/resize/presentation operations; server packing owns coordinates. Respect minimum sizes, mobile readability and existing settings. Multiple designs can satisfy a delegated request.
                Before returning, check operations against every explicit current/retained request. Preserve unrelated properties. Explanation must describe actual operations, not repeat desired effects or claim unperformed visual/outcome verification.
                """;
    }
}
