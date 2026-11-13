package com.voltcanary.dashboard.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Exact metadata identities, independent of samples and opaque AI references. */
public final class DashboardDataChoices {
    private DashboardDataChoices() { }
    public record View(String widgetType, int typeVersion, String purpose, List<String> visualizations,
            List<String> reducers, String readiness, List<String> requirements) { }
    public record Choice(String choiceId, UUID deviceId, UUID sourceId, String externalSourceId,
            String family, String key, String label, String valueType, String unit,
            String currentStatus, String sourceFreshness, String historyStatus, Instant recordedAt, List<View> views) { }
    public record Diagnostic(String code, UUID deviceId, String key, List<String> choiceIds, String message) { }
    public record Resolution(int descriptorVersion, String snapshotId, List<Choice> choices, List<Diagnostic> diagnostics) { }
}
