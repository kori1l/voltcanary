package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;

final class ConfiguredWidgetProjection {

    private ConfiguredWidgetProjection() {}

    static ObjectNode project(ObjectMapper mapper, Widget widget, JsonNode evidence) {
        var data = mapper.createObjectNode();
        if (NativeDashboardRows.TYPE.equals(widget.type())) NativeDashboardRows.project(
            data,
            evidence,
            widget.query()
        );
        else DashboardRuleMeasure.parse(widget.query()).project(data, evidence);
        return data;
    }
}
