package com.voltcanary.aiagent;

import com.voltcanary.aiagent.service.PlanningInputGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class WidgetCatalogueInputGuardTest {
    private final PlanningInputGuard guard = new PlanningInputGuard();

    private List<Map<String, Object>> catalogue() {
        var properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 24; i++) properties.put("option" + i, Map.of("type", List.of("boolean", "null"),
                "description", "A supported presentation option", "default", false));
        var widgets = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 18; i++) widgets.add(Map.of("type", "widget-" + i, "capabilities",
                Map.of("settingsSchema", Map.of("type", "object", "properties", properties))));
        return widgets;
    }

    @Test void fullOwnerCatalogueIsAcceptedButClientLimitsStayUnchanged() {
        var catalogue = catalogue();
        assertDoesNotThrow(() -> guard.inspectWidgetCatalogue(catalogue));
        assertThrows(ResponseStatusException.class, () -> guard.inspect(Map.of("dashboardWidgetTypes", catalogue)));
        assertThrows(ResponseStatusException.class, () -> guard.validate("client message", Map.of("dashboardWidgetTypes", catalogue)));
    }

    @Test void ownerCatalogueStillRejectsSecretsNonfiniteNumbersAndOversizedInput() {
        assertThrows(ResponseStatusException.class, () -> guard.inspectWidgetCatalogue(List.of(Map.of("apiKey", "secret"))));
        assertThrows(ResponseStatusException.class, () -> guard.inspectWidgetCatalogue(List.of(Map.of("label", "Bearer private-value"))));
        assertThrows(ResponseStatusException.class, () -> guard.inspectWidgetCatalogue(List.of(Map.of("default", Double.NaN))));
        assertThrows(ResponseStatusException.class, () -> guard.inspectWidgetCatalogue(java.util.Collections.nCopies(65, Map.of())));
        assertThrows(ResponseStatusException.class, () -> guard.inspectWidgetCatalogue(List.of(Map.of("label", "x".repeat(8001)))));
    }
}
