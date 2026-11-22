package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.domain.DashboardOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardOperationCompilerTest {

    @Test
    void explicitGlobalPeriodAndChartOverrideRemainIndependentDuringCompilation() {
        var result = compile(
            false,
            new DashboardOperation.Period("global", null, "LAST_6_HOURS"),
            new DashboardOperation.ShowHistory(
                "chart",
                "data_speedKmh",
                null,
                "line",
                "LAST_1_HOUR"
            )
        );
        assertThat(result.intent().get("period")).isEqualTo("LAST_6_HOURS");
        assertThat(
            ((Map<?, ?>) ((List<?>) result.intent().get("widgets")).getFirst()).get("period")
        ).isEqualTo("LAST_1_HOUR");
    }

    @Test
    void preparedDefinitionCannotDisagreeWithTheGlobalPeriod() {
        var intent = Map.<String, Object>of(
            "name",
            "Dashboard",
            "period",
            "LAST_6_HOURS",
            "widgets",
            List.of()
        );
        var definition = new LinkedHashMap<String, Object>(
            Map.of(
                "name",
                "Dashboard",
                "widgets",
                List.of(),
                "layouts",
                Map.of(),
                "defaultContext",
                Map.of("time", Map.of("preset", "LAST_24_HOURS"))
            )
        );
        var expected = new DashboardOperationCompiler.Result(
            intent,
            Map.of(),
            List.of(),
            false,
            null
        );
        var operations = new DashboardOperationProtocol.Envelope(
            DashboardOperationProtocol.VERSION,
            List.of(new DashboardOperation.Period("global", null, "LAST_6_HOURS"))
        );
        java.util.function.Supplier<com.voltcanary.aiagent.model.AgentPlan> prepared = () ->
            new com.voltcanary.aiagent.model.AgentPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "request",
                "summary",
                "explanation",
                List.of(),
                List.of(
                    new com.voltcanary.aiagent.model.AgentAction(
                        UUID.randomUUID(),
                        com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD,
                        "title",
                        "description",
                        true,
                        Map.of("prepareIntent", intent, "definition", definition)
                    )
                ),
                java.time.Instant.now()
            );
        var rejected = org.junit.jupiter.api.Assertions.assertThrows(
            com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class,
            () ->
                PrimaryDashboardVerification.verify(
                    mapper,
                    null,
                    prepared.get(),
                    expected,
                    operations,
                    context(false)
                )
        );
        assertThat(rejected.diagnostic().code()).isEqualTo("DASHBOARD_PERIOD_POSTCONDITION");
        definition.put("defaultContext", Map.of("time", Map.of("preset", "LAST_6_HOURS")));
        PrimaryDashboardVerification.verify(
            mapper,
            null,
            prepared.get(),
            expected,
            operations,
            context(false)
        );
    }

    @Test
    void independentLayoutVerificationRejectsCollateralReorderAndPresentationRepacking() throws Exception {
        var before = mapper.readTree(
            "{\"layouts\":{\"desktop\":[{\"widgetId\":\"map\",\"x\":0,\"y\":0},{\"widgetId\":\"card\",\"x\":0,\"y\":8},{\"widgetId\":\"history\",\"x\":0,\"y\":9}]}}"
        );
        var validMove = mapper.readTree(
            "{\"layouts\":{\"desktop\":[{\"widgetId\":\"card\",\"x\":0,\"y\":0},{\"widgetId\":\"map\",\"x\":0,\"y\":1},{\"widgetId\":\"history\",\"x\":0,\"y\":9}]}}"
        );
        var collateral = mapper.readTree(
            "{\"layouts\":{\"desktop\":[{\"widgetId\":\"card\",\"x\":0,\"y\":0},{\"widgetId\":\"history\",\"x\":0,\"y\":1},{\"widgetId\":\"map\",\"x\":0,\"y\":6}]}}"
        );
        PrimaryDashboardVerification.verifyLayouts(before, validMove, java.util.Set.of("card"));
        assertThatThrownBy(() ->
            PrimaryDashboardVerification.verifyLayouts(before, collateral, java.util.Set.of("card"))
        ).isInstanceOf(com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class);
        assertThatThrownBy(() ->
            PrimaryDashboardVerification.verifyLayouts(before, validMove, java.util.Set.of())
        ).isInstanceOf(com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
        delimiter = '|',
        textBlock = """
        {"desktop":{}} | INVALID_LAYOUT_BREAKPOINT
        {"desktop":[{"widgetId":"other","x":0,"y":0,"w":1,"h":1}]} | LAYOUT_WIDGET_IDENTITY_POSTCONDITION
        {"desktop":[{"widgetId":"card","x":0,"y":0,"w":1,"h":1},{"widgetId":"card","x":1,"y":0,"w":1,"h":1}]} | LAYOUT_WIDGET_IDENTITY_POSTCONDITION
        {"desktop":[{"widgetId":"card","y":0,"w":1,"h":1}]} | INVALID_LAYOUT_GEOMETRY
        {"desktop":[{"widgetId":"card","x":-1,"y":0,"w":1,"h":1}]} | INVALID_LAYOUT_GEOMETRY
        {"desktop":[{"widgetId":"card","x":0,"y":0,"w":1.5,"h":1}]} | INVALID_LAYOUT_GEOMETRY
        {"desktop":[{"widgetId":"card","x":0,"y":0,"w":2,"h":1},{"widgetId":"history","x":1,"y":0,"w":1,"h":1}]} | OVERLAPPING_LAYOUT
        """
    )
    void preparedLayoutsRejectInvalidPlacements(String layouts, String code) throws Exception {
        var layout = mapper.readTree(layouts);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class,
            () ->
                PrimaryDashboardVerification.verifyLayoutGeometry(
                    layout,
                    java.util.Set.of("card", "history")
                )
        );
        assertThat(failure.diagnostic().code()).isEqualTo(code);
    }

    @Test
    void preparedResponsiveLayoutsMustApplyCompiledOrderAndSize() throws Exception {
        var layouts = mapper.readTree(
            """
            {"desktop":[{"widgetId":"card","x":0,"y":0,"w":6,"h":3},{"widgetId":"note","x":6,"y":0,"w":6,"h":4}],
             "tablet":[{"widgetId":"card","x":0,"y":0,"w":3,"h":3},{"widgetId":"note","x":3,"y":0,"w":3,"h":4}],
             "mobile":[{"widgetId":"card","x":0,"y":0,"w":1,"h":3},{"widgetId":"note","x":0,"y":3,"w":1,"h":4}]}
            """
        );
        var widgets = Map.of(
            "card",
            mapper.readTree("{\"width\":\"HALF\",\"height\":3}"),
            "note",
            mapper.readTree("{\"width\":\"HALF\"}")
        );
        PrimaryDashboardVerification.verifyCompositionLayout(
            layouts,
            List.of("card", "note"),
            widgets
        );
        var wrongOrder = org.junit.jupiter.api.Assertions.assertThrows(
            com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class,
            () ->
                PrimaryDashboardVerification.verifyCompositionLayout(
                    layouts,
                    List.of("note", "card"),
                    widgets
                )
        );
        assertThat(wrongOrder.diagnostic().code()).isEqualTo("LAYOUT_ORDER_POSTCONDITION");
        ((com.fasterxml.jackson.databind.node.ObjectNode) layouts.at("/mobile/0")).put("h", 2);
        var wrongSize = org.junit.jupiter.api.Assertions.assertThrows(
            com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class,
            () ->
                PrimaryDashboardVerification.verifyCompositionLayout(
                    layouts,
                    List.of("card", "note"),
                    widgets
                )
        );
        assertThat(wrongSize.diagnostic().code()).isEqualTo("LAYOUT_SIZE_POSTCONDITION");
    }

    @Test
    void newWidgetTitleResolvesOnlyAnEarlierOperationWithoutChangingItsDataOrGeometry() {
        var created = compile(
            false,
            new DashboardOperation.ShowCurrent("speed", "data_speedKmh", null)
        );
        var renamed = compile(
            false,
            new DashboardOperation.ShowCurrent("speed", "data_speedKmh", null),
            new DashboardOperation.RenameWidget("title", "@speed", "Скорость сейчас")
        );
        var expected = new LinkedHashMap<>(
            (Map<String, Object>) ((List<?>) created.intent().get("widgets")).getFirst()
        );
        expected.put("title", "Скорость сейчас");
        assertThat(((List<?>) renamed.intent().get("widgets")).getFirst()).isEqualTo(expected);
        assertThatThrownBy(() ->
            compile(
                false,
                new DashboardOperation.RenameWidget("title", "@speed", "Скорость"),
                new DashboardOperation.ShowCurrent("speed", "data_speedKmh", null)
            )
        ).isInstanceOf(DashboardPreparationRejected.class);
    }

    @Test
    void strictSchemaOffersCreationRenameAndConstrainsServerBoundedArguments() {
        var variants = mapper
            .valueToTree(DashboardOperationSchema.response(context(false).context()))
            .path("properties")
            .path("actions")
            .path("items")
            .path("properties")
            .path("parameters")
            .path("properties")
            .path("operations")
            .path("items")
            .path("anyOf");
        var byName = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        variants.forEach(variant ->
            byName.put(
                variant.path("properties").path("op").path("enum").get(0).asText(),
                variant.path("properties")
            )
        );
        assertThat(byName).containsKey("rename_widget").doesNotContainKey("keep_review");
        assertThat(
            byName.get("rename_widget").path("widgetKey").path("pattern").asText()
        ).startsWith("^@");
        var positions = new ArrayList<String>();
        for (var variant : variants)
            if (variant.at("/properties/op/enum/0").asText().equals("place_widgets")) variant
                .at("/properties/position/enum")
                .forEach(position -> positions.add(position.asText()));
        assertThat(positions).containsExactly("TOP", "BOTTOM", "BEFORE", "AFTER");
        assertThat(
            byName.get("resize_widgets").path("height").path("anyOf").get(0).path("minimum").asInt()
        ).isEqualTo(1);
        assertThat(
            byName.get("resize_widgets").path("height").path("anyOf").get(0).path("maximum").asInt()
        ).isEqualTo(12);
        assertThat(byName.get("set_period").path("period").path("enum").size()).isEqualTo(5);
        assertThat(
            byName.get("show_history").path("view").path("anyOf").get(0).path("enum").toString()
        ).contains("horizontal-bar");
    }

    @Test
    void strictSchemaGroundsDataReferencesAndExcludesReviewOnlyInitialOperations() {
        var context = new LinkedHashMap<>(context(false).context());
        context.put(
            "dashboardDataChoices",
            List.of(
                Map.of(
                    "dataRef",
                    "actual_speed",
                    "views",
                    List.of(
                        Map.of("widgetType", "vehicle-value"),
                        Map.of("widgetType", "time-series")
                    )
                ),
                Map.of(
                    "dataRef",
                    "actual_doors",
                    "views",
                    List.of(
                        Map.of("widgetType", "vehicle-value"),
                        Map.of("widgetType", "vehicle-timeline")
                    )
                ),
                Map.of(
                    "dataRef",
                    "actual_position",
                    "views",
                    List.of(Map.of("widgetType", "vehicle-map"))
                )
            )
        );
        var variants = mapper
            .valueToTree(DashboardOperationSchema.response(context))
            .path("properties")
            .path("actions")
            .path("items")
            .path("properties")
            .path("parameters")
            .path("properties")
            .path("operations")
            .path("items")
            .path("anyOf");
        for (var variant : variants) {
            var properties = variant.path("properties");
            assertThat(properties.path("op").path("enum").get(0).asText()).isNotIn(
                "restore_review",
                "keep_review",
                "remove_widgets",
                "set_data"
            );
            if (properties.has("dataRef")) assertThat(
                properties.path("dataRef").path("enum").toString()
            ).isEqualTo(
                properties.path("op").path("enum").get(0).asText().equals("show_map")
                    ? "[\"actual_position\"]"
                    : "[\"actual_speed\",\"actual_doors\"]"
            );
        }
        context.put("dashboardDataChoices", List.of());
        var empty = mapper.valueToTree(DashboardOperationSchema.response(context)).toString();
        assertThat(empty).doesNotContain("show_current", "show_history", "show_map", "set_data");
        assertThat(empty).contains("rename_dashboard");
    }

    @Test
    void unresolvedSourceChoicePrecedesAnInvalidGeneratedPositionCard() {
        var original = context(false);
        var refs = new ArrayList<>(original.refs());
        var position = refs
            .stream()
            .filter(ref -> ref.ref().equals("data_position"))
            .findFirst()
            .orElseThrow();
        refs.remove(position);
        for (int index = 1; index <= 2; index++) {
            var choice = new LinkedHashMap<>(position.summary());
            choice.put("requiresSourceChoice", true);
            choice.put("sourceChoiceLabel", "Источник " + index);
            choice.put("externalSourceId", "fixture-source-" + index);
            refs.add(
                new AuthorizedPlanningContext.ResourceRef(
                    "position" + index,
                    "dashboard-data",
                    "choice" + index,
                    choice
                )
            );
        }
        var context = new AuthorizedPlanningContext.Prepared(
            original.scope(),
            original.context(),
            refs
        );
        var envelope = new DashboardOperationProtocol.Envelope(
            DashboardOperationProtocol.VERSION,
            List.of(new DashboardOperation.ShowCurrent("position", "position1", null))
        );
        assertThatThrownBy(() ->
            DashboardOperationCompiler.compile(envelope, context, "turn")
        ).isInstanceOf(DashboardSourceChoiceRequired.class);
        assertThatThrownBy(() ->
            DashboardDataReferences.resolve(
                Map.of("type", "vehicle-value", "dataRef", "position1"),
                context
            )
        ).isInstanceOf(DashboardSourceChoiceRequired.class);
    }

    @Test
    void aDifferentHistoryViewIsSeparateUnlessAnExistingTargetIsExplicit() {
        var added = compile(
            true,
            new DashboardOperation.ShowHistory("list", "data_doors", null, "list", null)
        );
        assertThat((List<?>) added.intent().get("widgets")).hasSize(6);
        assertThat(((List<?>) added.intent().get("widgets")).get(2)).isEqualTo(doorHistory);
        var changed = compile(
            true,
            new DashboardOperation.ShowHistory("list", "data_doors", "door_history", "list", null)
        );
        assertThat((List<?>) changed.intent().get("widgets")).hasSize(5);
        assertThat(
            ((Map<?, ?>) ((List<?>) changed.intent().get("widgets")).get(2)).get("visualization")
        ).isEqualTo("list");
    }

    @Test
    void legacyVehicleHistoryWithoutVisualizationMeansListRatherThanLine() {
        var original = context(true);
        var values = new LinkedHashMap<>(original.context());
        var previous = new LinkedHashMap<>(
            (Map<String, Object>) values.get("previousDashboardIntent")
        );
        var legacy = new LinkedHashMap<>(doorHistory);
        legacy.remove("visualization");
        previous.put("widgets", List.of(map, speed, legacy, speedHistory, stop));
        values.put("previousDashboardIntent", previous);
        var context = new AuthorizedPlanningContext.Prepared(
            original.scope(),
            values,
            original.refs()
        );
        var list = DashboardOperationCompiler.compile(
            new DashboardOperationProtocol.Envelope(
                DashboardOperationProtocol.VERSION,
                List.of(
                    new DashboardOperation.ShowHistory("list", "data_doors", null, "list", null)
                )
            ),
            context,
            "turn"
        );
        assertThat((List<?>) list.intent().get("widgets")).hasSize(5);
        var line = DashboardOperationCompiler.compile(
            new DashboardOperationProtocol.Envelope(
                DashboardOperationProtocol.VERSION,
                List.of(
                    new DashboardOperation.ShowHistory("line", "data_doors", null, "line", null)
                )
            ),
            context,
            "turn"
        );
        assertThat((List<?>) line.intent().get("widgets")).hasSize(6);
        assertThat(((List<?>) line.intent().get("widgets")).get(2)).isEqualTo(legacy);
    }

    @Test
    void explicitHistoryTargetPreservesOmittedViewAndPeriod() {
        var result = compile(
            true,
            new DashboardOperation.ShowHistory(
                "list",
                "data_doors",
                "door_history",
                "list",
                "LAST_6_HOURS"
            ),
            new DashboardOperation.ShowHistory("reuse", "data_doors", "door_history", null, null)
        );
        var chart = (Map<?, ?>) ((List<?>) result.intent().get("widgets")).get(2);
        assertThat(chart.get("visualization")).isEqualTo("list");
        assertThat(chart.get("period")).isEqualTo("LAST_6_HOURS");
    }

    @Test
    void explicitCurrentTargetKeepsItsTypeWhenAnotherCompatibleTypeIsPreferred() {
        var original = context(true);
        var refs = new ArrayList<>(original.refs());
        var choice = refs
            .stream()
            .filter(ref -> ref.ref().equals("data_speedKmh"))
            .findFirst()
            .orElseThrow();
        var summary = new LinkedHashMap<>(choice.summary());
        summary.put(
            "views",
            List.of(
                Map.of("widgetType", "latest-value", "visualizations", List.of("number")),
                Map.of("widgetType", "vehicle-value", "visualizations", List.of("number"))
            )
        );
        refs.set(
            refs.indexOf(choice),
            new AuthorizedPlanningContext.ResourceRef(
                choice.ref(),
                choice.kind(),
                choice.resourceId(),
                summary
            )
        );
        var context = new AuthorizedPlanningContext.Prepared(
            original.scope(),
            original.context(),
            refs
        );
        var result = DashboardOperationCompiler.compile(
            new DashboardOperationProtocol.Envelope(
                DashboardOperationProtocol.VERSION,
                List.of(new DashboardOperation.ShowCurrent("current", "data_speedKmh", "speed"))
            ),
            context,
            "turn"
        );
        assertThat(result.intent().get("widgets")).isEqualTo(
            List.of(map, speed, doorHistory, speedHistory, stop)
        );
    }

    @Test
    void replacementAddsOnlyItsAuthorizedDeviceToDashboardScope() {
        var original = context(true);
        var refs = new ArrayList<>(original.refs());
        refs.add(
            new AuthorizedPlanningContext.ResourceRef("second", "device", "second-id", Map.of())
        );
        refs.add(
            new AuthorizedPlanningContext.ResourceRef(
                "second-source",
                "source",
                "second-source-id",
                Map.of()
            )
        );
        refs.add(
            new AuthorizedPlanningContext.ResourceRef(
                "second-speed",
                "dashboard-data",
                "second-choice",
                Map.of(
                    "deviceId",
                    "second-id",
                    "deviceRef",
                    "second",
                    "sourceId",
                    "second-source-id",
                    "key",
                    "speedKmh",
                    "family",
                    "RECORDED_VEHICLE_FIELD",
                    "views",
                    List.of(
                        Map.of("widgetType", "vehicle-value", "visualizations", List.of("number"))
                    )
                )
            )
        );
        var result = DashboardOperationCompiler.compile(
            new DashboardOperationProtocol.Envelope(
                DashboardOperationProtocol.VERSION,
                List.of(new DashboardOperation.SetData("replace", "speed", "second-speed"))
            ),
            new AuthorizedPlanningContext.Prepared(original.scope(), original.context(), refs),
            "turn"
        );
        assertThat(result.intent().get("deviceRefs")).isEqualTo(List.of("device", "second"));
        assertThat(((List<?>) result.intent().get("widgets")).getFirst()).isEqualTo(map);
        assertThat(result.allowedChanges().get("$dashboard")).containsExactly("deviceRefs");
        var inheritedContext = new LinkedHashMap<>(original.context());
        var inherited = new LinkedHashMap<>(
            (Map<String, Object>) inheritedContext.get("previousDashboardIntent")
        );
        var inheritedMap = new LinkedHashMap<>(map);
        inheritedMap.remove("deviceRefs");
        inherited.put("widgets", List.of(inheritedMap, speed));
        inheritedContext.put("previousDashboardIntent", inherited);
        var inheritedResult = DashboardOperationCompiler.compile(
            new DashboardOperationProtocol.Envelope(
                DashboardOperationProtocol.VERSION,
                List.of(new DashboardOperation.SetData("replace", "speed", "second-speed"))
            ),
            new AuthorizedPlanningContext.Prepared(original.scope(), inheritedContext, refs),
            "turn"
        );
        assertThat(
            ((Map<?, ?>) ((List<?>) inheritedResult.intent().get("widgets")).getFirst()).get(
                "deviceRefs"
            )
        ).isEqualTo(List.of("device"));
        assertThat(inheritedResult.allowedChanges()).doesNotContainKey("map");
        var properties = new com.voltcanary.aiagent.config.AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(
            builder
        ).build();
        server
            .expect(
                org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                    "http://dashboards/dashboards/composition/prepare"
                )
            )
            .andExpect(
                org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                    "{\"deviceIds\":[\"device-id\",\"second-id\"]}"
                )
            )
            .andExpect(
                org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                    "$.widgets[1].deviceIds[0]"
                ).value("second-id")
            )
            .andExpect(
                org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                    "$.widgets[0].deviceIds[0]"
                ).value("device-id")
            )
            .andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                    "{\"status\":\"READY\",\"digest\":\"replacement\",\"definition\":{\"name\":\"Трамвай\",\"widgets\":[]},\"devices\":[]}",
                    org.springframework.http.MediaType.APPLICATION_JSON
                )
            );
        var values = new LinkedHashMap<>(original.context());
        values.put("dashboardPlanningProtocol", DashboardOperationProtocol.VERSION);
        var action = new com.voltcanary.aiagent.model.AgentAction(
            UUID.randomUUID(),
            com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD,
            "Replace",
            "Replace",
            true,
            Map.of(
                "protocol",
                DashboardOperationProtocol.VERSION,
                "operations",
                List.of(
                    Map.of(
                        "op",
                        "set_data",
                        "id",
                        "replace",
                        "widgetKey",
                        "speed",
                        "dataRef",
                        "second-speed"
                    )
                )
            )
        );
        var plan = new com.voltcanary.aiagent.model.AgentPlan(
            UUID.randomUUID(),
            original.scope().userId(),
            "Поменяй данные",
            "Replace",
            "Replace",
            List.of(),
            List.of(action),
            java.time.Instant.now()
        );
        var prepared = new DashboardCreationCompiler(properties, builder, mapper).compile(
            plan,
            new AuthorizedPlanningContext.Prepared(original.scope(), values, refs),
            true,
            "Bearer owner"
        );
        assertThat(prepared.actions().getFirst().parameters()).containsEntry(
            "prepareDigest",
            "replacement"
        );
        server.verify();
    }

    @Test
    void keepReviewIsExclusiveAndRequiresASelectedBaseWithoutCompletingPendingWork() {
        var keep = DashboardOperationProtocol.parse(
            Map.of(
                "protocol",
                DashboardOperationProtocol.VERSION,
                "operations",
                List.of(Map.of("op", "keep_review", "id", "keep"))
            ),
            mapper
        );
        assertThat(
            DashboardOperationCompiler.compile(keep, context(true), "turn").restoreSteps()
        ).isZero();
        assertThatThrownBy(() ->
            DashboardOperationCompiler.compile(keep, context(false), "turn")
        ).isInstanceOf(DashboardPreparationRejected.class);
        var original = context(true);
        var values = new LinkedHashMap<>(original.context());
        values.put("pendingDashboardChangeIds", List.of("older", "current"));
        assertThat(
            DashboardOperationCompiler.compile(
                keep,
                new AuthorizedPlanningContext.Prepared(original.scope(), values, original.refs()),
                "turn"
            ).allowedChanges()
        ).isEmpty();
        assertThatThrownBy(() ->
            DashboardOperationProtocol.parse(
                Map.of(
                    "protocol",
                    DashboardOperationProtocol.VERSION,
                    "operations",
                    List.of(
                        Map.of("op", "keep_review", "id", "keep"),
                        Map.of("op", "rename_dashboard", "id", "rename", "name", "Other")
                    )
                ),
                mapper
            )
        ).isInstanceOf(DashboardPreparationRejected.class);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> map = panel("map", "vehicle-map", null);
    private final Map<String, Object> speed = panel("speed", "vehicle-value", "speedKmh");
    private final Map<String, Object> doorHistory = panel(
        "door_history",
        "vehicle-timeline",
        "doors"
    );
    private final Map<String, Object> speedHistory = panel(
        "speed_history",
        "vehicle-timeline",
        "speedKmh"
    );
    private final Map<String, Object> stop = panel("stop", "vehicle-value", "nextStop");

    private Map<String, Object> panel(String key, String type, String field) {
        var panel = new LinkedHashMap<String, Object>(
            Map.of(
                "key",
                key,
                "type",
                type,
                "title",
                key,
                "deviceRefs",
                List.of("device"),
                "sourceRef",
                "source",
                "width",
                "FULL",
                "height",
                5
            )
        );
        if (field != null) panel.put("vehicleField", field);
        if (type.equals("vehicle-timeline")) panel.put("visualization", "line");
        return panel;
    }

    AuthorizedPlanningContext.Prepared context(boolean previous) {
        var values = new LinkedHashMap<String, Object>();
        values.put("name", "Трамвай");
        values.put("selectedDashboardDeviceRefs", List.of("device"));
        if (previous) values.put(
            "previousDashboardIntent",
            Map.of(
                "name",
                "Трамвай",
                "period",
                "LAST_1_HOUR",
                "deviceRefs",
                List.of("device"),
                "widgets",
                List.of(map, speed, doorHistory, speedHistory, stop)
            )
        );
        var settings = List.of(
            Map.of("key", "showTitle", "type", "BOOLEAN"),
            Map.of("key", "showDetails", "type", "BOOLEAN"),
            Map.of("key", "compact", "type", "AUTO_BOOLEAN")
        );
        values.put(
            "dashboardWidgetTypes",
            List.of("vehicle-map", "vehicle-value", "vehicle-timeline", "context-note")
                .stream()
                .map(type ->
                    Map.of("type", type, "capabilities", Map.of("settingDefinitions", settings))
                )
                .toList()
        );
        var refs = new ArrayList<AuthorizedPlanningContext.ResourceRef>();
        for (String type : List.of(
            "vehicle-map",
            "vehicle-value",
            "vehicle-timeline",
            "context-note"
        )) {
            refs.add(
                new AuthorizedPlanningContext.ResourceRef(
                    "widget-" + type,
                    "widget-descriptor",
                    type,
                    Map.of("settings", settings)
                )
            );
        }
        refs.add(
            new AuthorizedPlanningContext.ResourceRef("device", "device", "device-id", Map.of())
        );
        refs.add(
            new AuthorizedPlanningContext.ResourceRef("source", "source", "source-id", Map.of())
        );
        for (String field : List.of("speedKmh", "doors", "nextStop", "position")) {
            var choice = new LinkedHashMap<String, Object>(
                Map.of(
                    "deviceId",
                    "device-id",
                    "deviceRef",
                    "device",
                    "sourceId",
                    "source-id",
                    "family",
                    "RECORDED_VEHICLE_FIELD",
                    "label",
                    field,
                    "valueType",
                    field.equals("nextStop") ? "string" : "number"
                )
            );
            if (!field.equals("position")) choice.put("key", field);
            choice.put(
                "views",
                field.equals("position")
                    ? List.of(Map.of("widgetType", "vehicle-map", "visualizations", List.of("map")))
                    : List.of(
                          Map.of(
                              "widgetType",
                              "vehicle-value",
                              "visualizations",
                              List.of("number")
                          ),
                          Map.of(
                              "widgetType",
                              "vehicle-timeline",
                              "visualizations",
                              field.equals("nextStop") ? List.of("list") : List.of("line", "list")
                          )
                      )
            );
            refs.add(
                new AuthorizedPlanningContext.ResourceRef(
                    "data_" + field,
                    "dashboard-data",
                    "choice_" + field,
                    choice
                )
            );
        }
        return new AuthorizedPlanningContext.Prepared(
            new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()),
            values,
            refs
        );
    }

    private DashboardOperationCompiler.Result compile(
        boolean previous,
        DashboardOperation... operations
    ) {
        return DashboardOperationCompiler.compile(
            new DashboardOperationProtocol.Envelope(
                DashboardOperationProtocol.VERSION,
                List.of(operations)
            ),
            context(previous),
            "persisted-turn"
        );
    }

    @Test
    void widgetCountDiagnosticsDistinguishAnEmptyDashboardFromOverflow() {
        assertThatThrownBy(() ->
            compile(false, new DashboardOperation.RenameDashboard("name", "Example"))
        ).isInstanceOfSatisfying(DashboardPreparationRejected.class, failure ->
            assertThat(failure.code()).isEqualTo("DASHBOARD_WIDGETS_EMPTY")
        );
        var notes = java.util.stream.IntStream.rangeClosed(1, 13)
            .mapToObj(index ->
                new DashboardOperation.AddNote("note" + index, "Title " + index, "Text " + index)
            )
            .toArray(DashboardOperation[]::new);
        assertThatThrownBy(() -> compile(false, notes)).isInstanceOfSatisfying(
            DashboardPreparationRejected.class,
            failure -> {
                assertThat(failure.code()).isEqualTo("DASHBOARD_WIDGET_LIMIT_EXCEEDED");
                assertThat(failure.getMessage()).contains("13 widgets");
            }
        );
    }

    @Test
    void compoundEditReusesRolesAndMovesOnlyCurrentCardsAboveMapAndHistories() {
        var result = compile(
            true,
            new DashboardOperation.ShowCurrent("s", "data_speedKmh", null),
            new DashboardOperation.ShowCurrent("n", "data_nextStop", null),
            new DashboardOperation.ShowCurrent("d", "data_doors", null),
            new DashboardOperation.ShowHistory("h", "data_doors", null, "line", null),
            new DashboardOperation.Place("p", List.of("@s", "@n", "@d"), "TOP", null)
        );
        var widgets = (List<?>) result.intent().get("widgets");
        assertThat(widgets).hasSize(6);
        assertThat(widgets.get(0)).isEqualTo(speed);
        assertThat(widgets.get(1)).isEqualTo(stop);
        assertThat(widgets.subList(3, 6)).isEqualTo(List.of(map, doorHistory, speedHistory));
        assertThat(((Map<?, ?>) widgets.get(2)).get("type")).isEqualTo("vehicle-value");
        assertThat(result.responsiveReflow()).isTrue();
        assertThat(result.allowedChanges()).doesNotContainKeys(
            "map",
            "door_history",
            "speed_history"
        );
    }

    @Test
    void initialCreationAndRetryHaveStableServerKeysAndNoDuplicateRoles() {
        var operations = new DashboardOperation[] {
            new DashboardOperation.ShowCurrent("one", "data_doors", null),
            new DashboardOperation.ShowCurrent("two", "data_doors", null),
            new DashboardOperation.ShowHistory(
                "history",
                "data_doors",
                null,
                "line",
                "LAST_6_HOURS"
            ),
        };
        var first = compile(false, operations);
        assertThat(first.intent()).isEqualTo(compile(false, operations).intent());
        assertThat((List<?>) first.intent().get("widgets")).hasSize(2);
        assertThat(first.intent().get("deviceRefs")).isEqualTo(List.of("device"));
    }

    @Test
    void titleAndSettingsDoNotPermitGeometryOrBindingChangesAndNullResetsAreRetained() {
        var result = compile(
            true,
            new DashboardOperation.RenameWidget("rename", "map", "Положение"),
            new DashboardOperation.Presentation(
                "hide",
                List.of("ALL"),
                List.of(new DashboardOperation.SettingPatch("showTitle", false))
            ),
            new DashboardOperation.Presentation(
                "reset",
                List.of("speed"),
                List.of(new DashboardOperation.SettingPatch("showDetails", null))
            )
        );
        var panel = (Map<?, ?>) ((List<?>) result.intent().get("widgets")).getFirst();
        assertThat(panel.get("height")).isEqualTo(map.get("height"));
        assertThat(panel.get("sourceRef")).isEqualTo(map.get("sourceRef"));
        assertThat(panel.get("title")).isEqualTo("Положение");
        assertThat(result.responsiveReflow()).isFalse();
        assertThat(result.allowedChanges().get("map")).containsExactlyInAnyOrder(
            "title",
            "settings.showTitle"
        );
        var settings = (Map<?, ?>) (
            (Map<?, ?>) ((List<?>) result.intent().get("widgets")).get(1)
        ).get("settings");
        assertThat(settings.containsKey("showDetails")).isTrue();
        assertThat(settings.get("showDetails")).isNull();
    }

    @Test
    void rejectsConflictsUnknownSettingsForeignForwardRemovedAndDuplicateReferences() {
        var bad = List.of(
            List.of(
                new DashboardOperation.RenameWidget("a", "map", "a"),
                new DashboardOperation.RenameWidget("b", "map", "b")
            ),
            List.of(
                new DashboardOperation.Presentation(
                    "a",
                    List.of("map"),
                    List.of(new DashboardOperation.SettingPatch("query", "sql"))
                )
            ),
            List.of(new DashboardOperation.Place("a", List.of("@later"), "TOP", null)),
            List.of(new DashboardOperation.Place("a", List.of("map", "map"), "TOP", null)),
            List.of(new DashboardOperation.Place("a", List.of("map"), "BEFORE", "map")),
            List.of(
                new DashboardOperation.Remove("a", List.of("map")),
                new DashboardOperation.RenameWidget("b", "map", "b")
            ),
            List.of(new DashboardOperation.ShowCurrent("a", "foreign", null)),
            List.of(new DashboardOperation.Resize("a", List.of("map"), "FULL", 0, null)),
            List.of(new DashboardOperation.Resize("a", List.of("map"), "FULL", 2, 50)),
            List.of(new DashboardOperation.ShowCurrent("a", "data_doors", "door_history")),
            List.of(new DashboardOperation.ShowHistory("a", "data_nextStop", null, "line", null))
        );
        for (var operations : bad)
            assertThatThrownBy(() ->
                compile(true, operations.toArray(DashboardOperation[]::new))
            ).isInstanceOf(DashboardPreparationRejected.class);
    }

    @Test
    void strictProtocolRejectsTechnicalFieldsDuplicateIdsOversizeMixedRestoreAndInvalidTypes() throws Exception {
        for (String invalid : List.of(
            "[{\"op\":\"rename_widget\",\"id\":\"a\",\"widgetKey\":\"map\",\"title\":\"X\",\"query\":{}}]",
            "[{\"op\":\"restore_review\",\"id\":\"a\",\"restoreSteps\":1},{\"op\":\"rename_dashboard\",\"id\":\"b\",\"name\":\"X\"}]",
            "[{\"op\":\"rename_dashboard\",\"id\":\"a\",\"name\":\"X\"},{\"op\":\"rename_dashboard\",\"id\":\"a\",\"name\":\"Y\"}]",
            "[{\"op\":\"resize_widgets\",\"id\":\"a\",\"targets\":[\"map\"],\"height\":1.5}]",
            "[]"
        )) {
            Map<String, Object> values = mapper.readValue(
                "{\"protocol\":\"dashboard-operations-v1\",\"operations\":" + invalid + "}",
                Map.class
            );
            assertThatThrownBy(() -> DashboardOperationProtocol.parse(values, mapper)).isInstanceOf(
                DashboardPreparationRejected.class
            );
        }
        var many = java.util.stream.IntStream.range(0, 25)
            .mapToObj(i -> Map.of("op", "rename_dashboard", "id", "a" + i, "name", "x"))
            .toList();
        assertThatThrownBy(() ->
            DashboardOperationProtocol.parse(
                Map.of("protocol", DashboardOperationProtocol.VERSION, "operations", many),
                mapper
            )
        ).isInstanceOf(DashboardPreparationRejected.class);
    }

    @Test
    void globalAndLocalPeriodsSizingAndRemovalAreScoped() {
        var result = compile(
            true,
            new DashboardOperation.Resize("size", List.of("speed"), "THIRD", null, 50),
            new DashboardOperation.Period("period", List.of("speed_history"), "LAST_6_HOURS"),
            new DashboardOperation.Remove("remove", List.of("stop"))
        );
        var panels = (List<?>) result.intent().get("widgets");
        assertThat(panels).hasSize(4);
        assertThat(panels.getFirst()).isEqualTo(map);
        assertThat(((Map<?, ?>) panels.get(1)).get("height")).isEqualTo(3);
        assertThat(((Map<?, ?>) panels.get(3)).get("period")).isEqualTo("LAST_6_HOURS");
        assertThat(result.intent().get("period")).isEqualTo("LAST_1_HOUR");
    }

    @Test
    void restorationOnlyReturnsDistanceForTheExistingAuthorizedHistoryBoundary() {
        var restore = compile(true, new DashboardOperation.Restore("back", 1));
        assertThat(restore.restoreSteps()).isEqualTo(1);
        assertThat(restore.intent()).isEmpty();
        assertThatThrownBy(() ->
            compile(true, new DashboardOperation.Restore("back", 0))
        ).isInstanceOf(DashboardPreparationRejected.class);
    }

    @Test
    void explicitDataReplacementKeepsRoleAndGeometryAndExpectsTheResolvedNewBinding() {
        var result = compile(
            true,
            new DashboardOperation.SetData("replace", "speed", "data_doors")
        );
        var panel = (Map<?, ?>) ((List<?>) result.intent().get("widgets")).get(1);
        assertThat(panel.get("type")).isEqualTo(speed.get("type"));
        assertThat(panel.get("height")).isEqualTo(speed.get("height"));
        assertThat(panel.get("dataRef")).isEqualTo("data_doors");
        assertThat(result.responsiveReflow()).isFalse();
        assertThat(result.expectedOutcomes()).anySatisfy(outcome -> {
            assertThat(outcome.get("target")).isEqualTo("speed");
            Map<?, ?> expected = (Map<?, ?>) outcome.get("expected");
            assertThat(expected.get("vehicleField")).isEqualTo("doors");
            assertThat(expected.get("sourceRef")).isEqualTo("source");
        });
    }

    @Test
    void distinctPeriodsAreSeparateRolesAndAmbiguousMatchingRequiresAnExplicitTarget() {
        var result = compile(
            true,
            new DashboardOperation.ShowHistory("six", "data_doors", null, "line", "LAST_6_HOURS")
        );
        assertThat((List<?>) result.intent().get("widgets")).hasSize(6);
        var original = context(true);
        var previous = new LinkedHashMap<>(
            (Map<String, Object>) original.context().get("previousDashboardIntent")
        );
        var panels = new ArrayList<Object>((List<?>) previous.get("widgets"));
        var duplicate = new LinkedHashMap<>(doorHistory);
        duplicate.put("key", "door_history_second");
        panels.add(duplicate);
        previous.put("widgets", panels);
        var values = new LinkedHashMap<>(original.context());
        values.put("previousDashboardIntent", previous);
        var ambiguous = new AuthorizedPlanningContext.Prepared(
            original.scope(),
            values,
            original.refs()
        );
        var show = new DashboardOperationProtocol.Envelope(
            DashboardOperationProtocol.VERSION,
            List.of(new DashboardOperation.ShowHistory("history", "data_doors", null, "line", null))
        );
        assertThatThrownBy(() ->
            DashboardOperationCompiler.compile(show, ambiguous, "turn")
        ).isInstanceOf(DashboardPreparationRejected.class);
        var targeted = new DashboardOperationProtocol.Envelope(
            DashboardOperationProtocol.VERSION,
            List.of(
                new DashboardOperation.ShowHistory(
                    "history",
                    "data_doors",
                    "door_history_second",
                    "line",
                    null
                )
            )
        );
        assertThat(
            (List<?>) DashboardOperationCompiler.compile(targeted, ambiguous, "turn")
                .intent()
                .get("widgets")
        ).hasSize(6);
    }

    @Test
    void noteDashboardRenameAndGlobalPeriodAreBoundedAndPreserveExistingPanels() {
        var result = compile(
            true,
            new DashboardOperation.AddNote("note", "Примечание", "Проверить двери"),
            new DashboardOperation.RenameDashboard("name", "Новый трамвай"),
            new DashboardOperation.Period("period", List.of(), "LAST_6_HOURS")
        );
        assertThat(result.intent())
            .containsEntry("name", "Новый трамвай")
            .containsEntry("period", "LAST_6_HOURS");
        assertThat(((List<?>) result.intent().get("widgets")).subList(0, 5)).isEqualTo(
            List.of(map, speed, doorHistory, speedHistory, stop)
        );
        assertThatThrownBy(() ->
            compile(true, new DashboardOperation.AddNote("note", "Note", "x".repeat(1001)))
        ).isInstanceOf(DashboardPreparationRejected.class);
    }

    @Test
    void schemaVariantsExactlyMatchSealedJavaOperationFields() {
        var schema = mapper.valueToTree(DashboardOperationSchema.envelope());
        var variants = schema.path("properties").path("operations").path("items").path("anyOf");
        var names = new java.util.HashSet<String>();
        variants.forEach(variant -> names.add(variant.at("/properties/op/enum/0").asText()));
        assertThat(names.size()).isEqualTo(
            DashboardOperation.class.getPermittedSubclasses().length
        );
        for (var subtype : DashboardOperation.class
            .getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class)
            .value()) {
            assertThat(names).contains(subtype.name());
            for (var variant : variants)
                if (variant.at("/properties/op/enum/0").asText().equals(subtype.name())) {
                    assertThat(variant.path("properties").size()).isEqualTo(
                        subtype.value().getRecordComponents().length + 1
                    );
                    assertThat(variant.path("additionalProperties").asBoolean()).isFalse();
                    for (var field : subtype.value().getRecordComponents())
                        assertThat(variant.path("properties").has(field.getName())).isTrue();
                }
        }
    }

    @Test
    void strictProviderSchemaCanOnlyTargetExistingReviewKeysAndCannotInventACreationKey() {
        var initial = mapper
            .valueToTree(DashboardOperationSchema.response(context(false).context()))
            .path("properties")
            .path("actions")
            .path("items")
            .path("properties")
            .path("parameters")
            .path("properties")
            .path("operations")
            .path("items")
            .path("anyOf");
        for (var variant : initial) {
            String op = variant.path("properties").path("op").path("enum").get(0).asText();
            if (op.startsWith("show_")) assertThat(
                variant.path("properties").path("widgetKey").path("type").asText()
            ).isEqualTo("null");
            assertThat(op).isNotEqualTo("set_data");
            if (op.equals("rename_widget")) assertThat(
                variant.path("properties").path("widgetKey").path("pattern").asText()
            ).startsWith("^@");
        }
        var refinement = mapper
            .valueToTree(DashboardOperationSchema.response(context(true).context()))
            .path("properties")
            .path("actions")
            .path("items")
            .path("properties")
            .path("parameters")
            .path("properties")
            .path("operations")
            .path("items")
            .path("anyOf");
        for (var variant : refinement)
            if (
                variant.path("properties").has("widgetKey") &&
                !variant
                    .path("properties")
                    .path("op")
                    .path("enum")
                    .get(0)
                    .asText()
                    .equals("rename_widget")
            ) {
                assertThat(
                    variant
                        .path("properties")
                        .path("widgetKey")
                        .path("anyOf")
                        .get(0)
                        .path("enum")
                        .toString()
                ).isEqualTo("[\"map\",\"speed\",\"door_history\",\"speed_history\",\"stop\"]");
            }
    }
}
