package com.voltcanary.aiagent.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** The model's bounded vocabulary. Resource identities and geometry are never model arguments. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DashboardOperation.ShowMap.class, name = "show_map"),
    @JsonSubTypes.Type(value = DashboardOperation.ShowCurrent.class, name = "show_current"),
    @JsonSubTypes.Type(value = DashboardOperation.ShowHistory.class, name = "show_history"),
    @JsonSubTypes.Type(value = DashboardOperation.AddNote.class, name = "add_note"),
    @JsonSubTypes.Type(value = DashboardOperation.Place.class, name = "place_widgets"),
    @JsonSubTypes.Type(value = DashboardOperation.Resize.class, name = "resize_widgets"),
    @JsonSubTypes.Type(value = DashboardOperation.Presentation.class, name = "set_presentation"),
    @JsonSubTypes.Type(value = DashboardOperation.RenameWidget.class, name = "rename_widget"),
    @JsonSubTypes.Type(value = DashboardOperation.SetData.class, name = "set_data"),
    @JsonSubTypes.Type(value = DashboardOperation.Remove.class, name = "remove_widgets"),
    @JsonSubTypes.Type(value = DashboardOperation.RenameDashboard.class, name = "rename_dashboard"),
    @JsonSubTypes.Type(value = DashboardOperation.Period.class, name = "set_period"),
    @JsonSubTypes.Type(value = DashboardOperation.Restore.class, name = "restore_review"),
    @JsonSubTypes.Type(value = DashboardOperation.Keep.class, name = "keep_review")
})
public sealed interface DashboardOperation {
    String id();
    record ShowMap(String id, String dataRef, String widgetKey) implements DashboardOperation { }
    record ShowCurrent(String id, String dataRef, String widgetKey) implements DashboardOperation { }
    record ShowHistory(String id, String dataRef, String widgetKey, String view, String period) implements DashboardOperation { }
    record AddNote(String id, String title, String text) implements DashboardOperation { }
    record Place(String id, List<String> targets, String position, String anchor) implements DashboardOperation { }
    record Resize(String id, List<String> targets, String width, Integer height, Integer heightPercent) implements DashboardOperation { }
    record SettingPatch(String key, Object value) { }
    record Presentation(String id, List<String> targets, List<SettingPatch> settings) implements DashboardOperation { }
    record RenameWidget(String id, String widgetKey, String title) implements DashboardOperation { }
    record SetData(String id, String widgetKey, String dataRef) implements DashboardOperation { }
    record Remove(String id, List<String> targets) implements DashboardOperation { }
    record RenameDashboard(String id, String name) implements DashboardOperation { }
    record Period(String id, List<String> targets, String period) implements DashboardOperation { }
    record Restore(String id, Integer restoreSteps) implements DashboardOperation { }
    record Keep(String id) implements DashboardOperation { }
}
