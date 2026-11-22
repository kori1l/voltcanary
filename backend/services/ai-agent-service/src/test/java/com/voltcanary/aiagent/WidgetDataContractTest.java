package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.service.DashboardOperationProtocol;
import com.voltcanary.aiagent.service.DashboardSkillContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WidgetDataContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final Map<String, Object> context = Map.of(
        "previousDashboardIntent",
        Map.of(
            "widgets",
            List.of(
                Map.of("key", "current", "type", "latest-value"),
                Map.of("key", "history", "type", "time-series", "visualization", "bar")
            )
        ),
        "dashboardDataChoices",
        List.of(
            Map.of(
                "dataRef",
                "metric",
                "views",
                List.of(
                    Map.of("widgetType", "latest-value"),
                    Map.of("widgetType", "time-series", "visualizations", List.of("line", "bar"))
                )
            ),
            Map.of("dataRef", "field", "views", List.of(Map.of("widgetType", "vehicle-value"))),
            Map.of(
                "dataRef",
                "line_only",
                "views",
                List.of(Map.of("widgetType", "time-series", "visualizations", List.of("line")))
            )
        )
    );

    @Test
    void replacementSchemaPreservesWidgetTypeAndVisualizationCompatibility() {
        var before = mapper.valueToTree(context);
        var schema = DashboardSkillContract.schema(mapper, DashboardSkillContract.REFINE, context);
        var valid = replace("current", "metric");
        validator.validateRegistered(schema, valid);
        DashboardSkillContract.validate(
            mapper,
            validator,
            DashboardSkillContract.REFINE,
            valid,
            context
        );
        validator.validateRegistered(schema, replace("history", "metric"));
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateRegistered(schema, replace("current", "field"))
        );
        validator.validateRegistered(schema, replace("history", "line_only"));
        assertThrows(IllegalArgumentException.class, () ->
            DashboardSkillContract.validate(
                mapper,
                validator,
                DashboardSkillContract.REFINE,
                replace("history", "line_only"),
                context
            )
        );
        assertEquals(before, mapper.valueToTree(context));
        var invalid = assertThrows(IllegalArgumentException.class, () ->
            DashboardSkillContract.validate(
                mapper,
                validator,
                DashboardSkillContract.REFINE,
                replace("current", "field"),
                context
            )
        );
        assertTrue(invalid.getMessage().contains("/operations/0/dataRef"));
        assertTrue(invalid.getMessage().contains("allowedDataRefs=[metric]"));
    }

    @Test
    void precedingViewChangeAllowsCompatibleReplacementWithoutChangingTheAcceptedSnapshot() {
        var before = mapper.valueToTree(context);
        var payload = envelope();
        payload
            .putArray("operations")
            .addObject()
            .put("op", "show_history")
            .put("id", "view")
            .put("dataRef", "metric")
            .put("widgetKey", "history")
            .put("view", "line")
            .putNull("period");
        payload
            .withArray("operations")
            .addObject()
            .put("op", "set_data")
            .put("id", "replace")
            .put("widgetKey", "history")
            .put("dataRef", "line_only");
        DashboardSkillContract.validate(
            mapper,
            validator,
            DashboardSkillContract.REFINE,
            payload,
            context
        );
        assertEquals(before, mapper.valueToTree(context));
    }

    @Test
    void placementAndTargetsExcludeInvalidCombinationsAndKeepGlobalPeriodLegal() {
        var schema = DashboardSkillContract.schema(mapper, DashboardSkillContract.REFINE, context);
        var payload = envelope();
        var op = payload
            .withArray("operations")
            .addObject()
            .put("op", "place_widgets")
            .put("id", "place")
            .put("position", "TOP")
            .putNull("anchor");
        op.putArray("targets").add("current");
        validator.validateRegistered(schema, payload);
        op.put("anchor", "history");
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateRegistered(schema, payload)
        );
        op.put("position", "BEFORE");
        validator.validateRegistered(schema, payload);
        op.putNull("anchor");
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateRegistered(schema, payload)
        );
        op.put("position", "TOP").putArray("targets");
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateRegistered(schema, payload)
        );
        payload
            .putArray("operations")
            .addObject()
            .put("op", "set_period")
            .put("id", "period")
            .put("period", "LAST_6_HOURS")
            .putArray("targets");
        validator.validateRegistered(schema, payload);
    }

    private ObjectNode replace(String widget, String data) {
        var result = envelope();
        result
            .withArray("operations")
            .addObject()
            .put("op", "set_data")
            .put("id", "replace")
            .put("widgetKey", widget)
            .put("dataRef", data);
        return result;
    }

    private ObjectNode envelope() {
        return mapper.createObjectNode().put("protocol", DashboardOperationProtocol.VERSION);
    }
}
