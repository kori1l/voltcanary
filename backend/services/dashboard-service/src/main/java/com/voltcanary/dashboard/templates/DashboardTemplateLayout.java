package com.voltcanary.dashboard.templates;

import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Models choose ordered template keys and sizes, never coordinates or executable queries. */
public final class DashboardTemplateLayout {

    public record Panel(String key, String width, Integer height) {}

    public List<DashboardTaskTemplates.Panel> select(
        DashboardTaskTemplates.Template template,
        List<Panel> layout
    ) {
        if (layout == null) return template.panels();
        if (layout.isEmpty() || layout.size() > 12) throw invalid("Select 1–12 template panels");
        Set<String> seen = new HashSet<>();
        return layout
            .stream()
            .map(item -> {
                if (item == null || item.key() == null || !seen.add(item.key())) throw invalid(
                    "Panel keys must be distinct"
                );
                if (
                    item.width() != null && !Set.of("FULL", "HALF", "THIRD").contains(item.width())
                ) {
                    throw invalid("Panel width must be FULL, HALF or THIRD");
                }
                if (
                    item.height() != null && (item.height() < 1 || item.height() > 12)
                ) throw invalid("Panel height must be 1–12 rows");
                return template
                    .panels()
                    .stream()
                    .filter(panel -> panel.key().equals(item.key()))
                    .findFirst()
                    .orElseThrow(() -> invalid("Panel key is not part of the selected template"));
            })
            .toList();
    }

    public int width(Panel panel, int columns) {
        String choice = panel == null || panel.width() == null ? "FULL" : panel.width();
        return Math.max(1, columns / (choice.equals("HALF") ? 2 : choice.equals("THIRD") ? 3 : 1));
    }

    private DashboardFailure invalid(String message) {
        return new DashboardFailure("VALIDATION_ERROR", message, "panelLayout");
    }
}
