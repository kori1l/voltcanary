package com.voltcanary.aiagent.service;

/** General design decisions from actual data; no device-specific dashboard recipes. */
public final class DashboardDesignGuide {

    public static final String VERSION = "dashboard-design-v3";

    private DashboardDesignGuide() {}

    public static String text() {
        return (
            VERSION +
            "\n" +
            """
            Responsibility: select content and hierarchy from actual purpose, data choices and capabilities. No mandatory widget bundle exists. Planner routes workflows/resources; this skill owns domain choices.
            User control: requested metrics, roles, views, text, periods, order and size are hard constraints. For new dashboards, design unspecified aspects. For edits, preserve existing choices unless the user changes them or explicitly delegates redesign. Delegated design means useful selection. Ask about material ambiguity or conflicts. Disclose unsupported views and offer supported alternatives.
            Read valueType, unit, format, family, CURRENT/HISTORY views, settings and freshness. Identifiers are not continuous measurements; strings may be text/categories/timestamps. Preserve exact strings, leading zeros and declared time/zone semantics. false, zero and empty strings are not null. Never invent observations or describe missing/stale data as fresh. Requested label/footer visibility changes do not change freshness or supply missing readings.
            Enumerate requested (field, role) pairs, including retained goals. Current state and history need separate widgets for the same field. Reuse matches and add requested missing roles using offered dataRef; place new widgets with @operationId. Use offered list views for text history and state views for Boolean/category history.
            Group related widgets when designing a layout. For a requested shared row, HALF fits two equal widgets and THIRD fits three. Mobile stacks the group in order. Server packing owns coordinates.
            Before returning, check operations against every explicit current/retained request. Preserve unrelated properties. Explanation must describe actual operations, not repeat desired effects or claim unperformed visual/outcome verification.
            """
        );
    }
}
