package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.voltcanary.dashboard.application.DashboardAccess;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.domain.DashboardRecords.Revision;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardStorage;
import com.voltcanary.dashboard.query.DashboardWidgetQueries;
import com.voltcanary.dashboard.query.QueryReadScheduler;
import com.voltcanary.dashboard.query.QueryTimeResolver;
import com.voltcanary.dashboard.query.WidgetEvidenceProjection;
import com.voltcanary.dashboard.query.WidgetManifests;
import com.voltcanary.dashboard.query.WidgetQueryDecoder;
import com.voltcanary.dashboard.query.WidgetScopeResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardWidgetQueriesTest {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final DashboardActor actor = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
    private final UUID id = UUID.randomUUID();
    private final DashboardStorage storage = mock(DashboardStorage.class);
    private final DashboardEvidenceReads reads = mock(DashboardEvidenceReads.class);
    private final List<DashboardEvidenceReads.Device> devices = new ArrayList<>();
    private QueryReadScheduler scheduler;
    private DashboardWidgetQueries queries;
    private boolean denied;

    @BeforeEach void setup() {
        for (int i = 0; i < 250; i++) devices.add(new DashboardEvidenceReads.Device(UUID.randomUUID(), "Device " + i,
                "SENSOR", null, "ACTIVE", 60L, "SOURCE_CONFIG", "PERIODIC", Instant.EPOCH));
        when(storage.findOwned(actor, id)).thenReturn(Optional.of(new Dashboard(id, actor.userId(), actor.accountId(),
                "Test", null, "ACTIVE", 1, 1, Instant.EPOCH, Instant.EPOCH)));
        when(reads.scope(anyString(), any(), any())).thenAnswer(call -> {
            var scope = (com.voltcanary.dashboard.query.QueryTypes.Scope) call.getArgument(2);
            var selected = scope.mode() == com.voltcanary.dashboard.query.QueryTypes.ScopeMode.UNBOUND ? List.<DashboardEvidenceReads.Device>of()
                    : scope.deviceIds().isEmpty() ? devices : devices.stream().filter(device -> scope.deviceIds().contains(device.id())).toList();
            return new DashboardEvidenceReads.ScopeSnapshot(actor.userId(), actor.accountId(), selected.size(), selected.size(),
                    denied ? 1 : 0, selected, List.of(), false, Instant.now());
        });
        when(reads.telemetry(anyString(), any(), any())).thenAnswer(call -> evidence((DashboardEvidenceReads.ReadRequest)call.getArgument(2)));
        when(reads.incidents(anyString(), any(), any())).thenAnswer(call -> evidence((DashboardEvidenceReads.ReadRequest)call.getArgument(2)));
        scheduler = new QueryReadScheduler(reads);
        queries = new DashboardWidgetQueries(new com.voltcanary.dashboard.query.QueryDefinitionReader(new DashboardAccess(storage), storage), new WidgetManifests(),
                new WidgetQueryDecoder(mapper), new WidgetScopeResolver(reads), new QueryTimeResolver(), scheduler,
                new WidgetEvidenceProjection(mapper), mapper);
    }
    @AfterEach void close() { scheduler.close(); }
    private com.fasterxml.jackson.databind.node.ObjectNode evidence(DashboardEvidenceReads.ReadRequest request) {
        var node = mapper.createObjectNode().put("evaluatedCount", request.deviceIds().size()).put("deniedBindingCount", 0);
        node.set("authorizedDeviceIds", mapper.valueToTree(request.deviceIds()));
        node.putArray("latest"); node.putArray("summaries"); node.putArray("baselineSummaries");
        return node;
    }
    private Widget widget(String widgetId, String type, com.fasterxml.jackson.databind.JsonNode binding) {
        return new Widget(widgetId, type, 1, type, binding, null, null, null, null);
    }
    private void definition(List<Widget> widgets) {
        var definition = new DashboardDefinition(1, "Test", null, null, List.of(), widgets, null);
        when(storage.revision(id, 1)).thenReturn(Optional.of(new Revision(id, 1, definition, actor.userId(), Instant.EPOCH)));
    }
    private DashboardWidgetQueries.Request request(boolean force) { return new DashboardWidgetQueries.Request(null, null, null, force); }

    @Test void full250ScopeAndIdenticalPanelsDeduplicateAndRenewAuthorizationBeforeCache() {
        definition(List.of(widget("first", "fleet-count", null), widget("second", "fleet-count", null)));
        var first = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(first.panels()).allSatisfy(panel -> {
            assertThat(panel.status()).isEqualTo("ready");
            assertThat(panel.data().path("counts").path("registered").asInt()).isEqualTo(250);
            assertThat(panel.evaluatedCount()).isEqualTo(250);
        });
        var cached = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(cached.cached()).isTrue(); assertThat(cached.asOf()).isEqualTo(first.asOf());
        verify(reads, times(1)).telemetry(anyString(), any(), any());
        verify(reads, times(2)).scope(anyString(), any(), any());
        denied = true;
        var revoked = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(revoked.cached()).isFalse();
        assertThat(revoked.panels()).allSatisfy(panel -> { assertThat(panel.status()).isEqualTo("no_access"); assertThat(panel.data()).isNull(); });
        verify(storage, never()).audit(any(), any(), org.mockito.ArgumentMatchers.anyInt(), anyString(), any());
        verify(storage, never()).saveDraft(any()); verify(storage, never()).appendRevision(any());
    }
    @Test void fixedWidgetRetainsOwnDeviceWhenRuntimeFilterChanges() {
        var fixed = mapper.createObjectNode().put("mode", "FIXED"); fixed.putArray("deviceIds").add(devices.getFirst().id().toString());
        definition(List.of(widget("kitchen", "fleet-count", fixed), widget("dynamic", "fleet-count", null)));
        var runtime = mapper.createObjectNode(); runtime.putObject("scope").put("mode", "DYNAMIC").putArray("deviceIds").add(devices.get(1).id().toString());
        var result = queries.query("Bearer fixture", actor, id, new DashboardWidgetQueries.Request(null, null, runtime, false));
        assertThat(result.panels().getFirst().data().path("devices").get(0).path("id").asText()).isEqualTo(devices.getFirst().id().toString());
        assertThat(result.panels().get(1).data().path("devices").get(0).path("id").asText()).isEqualTo(devices.get(1).id().toString());
        assertThat(result.panels()).extracting(panel -> panel.time().asOf()).containsOnly(result.asOf());
    }
    @Test void partialOutageDoesNotHideIndependentIncidentAndUnknownWidgetsStayInert() {
        definition(List.of(widget("telemetry", "fleet-count", null), widget("incidents", "incident-list", null), widget("future", "future-widget", null)));
        org.mockito.Mockito.doThrow(new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Fixture outage")).when(reads).telemetry(anyString(), any(), any());
        var result = queries.query("Bearer fixture", actor, id, request(true));
        assertThat(result.panels()).extracting(panel -> panel.status()).containsExactly("unavailable", "ready", "unsupported");
        assertThat(result.panels().getFirst().data()).isNull();
    }
    @Test void foreignDashboardDoesNotReadOwnersAndActorCacheIsIsolated() {
        definition(List.of(widget("fleet", "fleet-count", null)));
        queries.query("Bearer fixture", actor, id, request(false));
        var foreign = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
        when(storage.findOwned(foreign, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> queries.query("Bearer foreign", foreign, id, request(false))).isInstanceOf(DashboardFailure.class);
        verify(reads, times(1)).scope(anyString(), any(), any());
    }
    @Test void unsupportedQueryFieldsAndDetailBudgetsAreTypedPanelFailures() {
        var invalid = mapper.createObjectNode().put("sql", "select arbitrary");
        definition(List.of(new Widget("bad", "fleet-count", 1, "Bad", null, invalid, null, null, null)));
        var result = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(result.panels()).extracting(panel -> panel.errorCode()).containsExactly("VALIDATION_ERROR");
        definition(List.of(widget("detail", "prediction-evidence", null)));
        assertThatThrownBy(() -> queries.query("Bearer fixture", actor, id, request(false)))
                .isInstanceOfSatisfying(DashboardFailure.class, failure -> assertThat(failure.code()).isEqualTo("LIMIT_EXCEEDED"));
        verify(reads, never()).analytics(anyString(), any(), any());
    }
    @Test void concurrentOwnerReadsNeverExceedSixAndValidationDoesNotReadFacts() {
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try { Thread.sleep(80); return evidence(call.getArgument(2)); }
            finally { active.decrementAndGet(); }
        }).when(reads).telemetry(anyString(), any(), any());
        List<Widget> widgets = new ArrayList<>();
        for (int index = 1; index <= 24; index++) widgets.add(new Widget("fleet" + index, "fleet-count", 1, "Fleet", null,
                mapper.createObjectNode().put("rows", index), null, null, null));
        definition(widgets);
        var result = queries.query("Bearer fixture", actor, id, request(true));
        assertThat(result.panels()).allSatisfy(panel -> assertThat(panel.status()).isEqualTo("ready"));
        assertThat(maximum.get()).isEqualTo(6);
        var validated = queries.query("Bearer fixture", actor, id, request(true), true);
        assertThat(validated.panels()).allSatisfy(panel -> { assertThat(panel.status()).isEqualTo("valid"); assertThat(panel.data()).isNull(); });
        verify(reads, times(24)).telemetry(anyString(), any(), any());
    }
    @Test void timeoutKeepsIndependentPanelsAndDoesNotFabricateZero() {
        org.mockito.Mockito.doAnswer(call -> { Thread.sleep(10000); return evidence(call.getArgument(2)); })
                .when(reads).telemetry(anyString(), any(), any());
        definition(List.of(widget("blocked", "fleet-count", null), widget("incidents", "incident-list", null)));
        long start = System.nanoTime();
        var result = queries.query("Bearer fixture", actor, id, request(true));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(4900L, 6000L);
        assertThat(result.panels().getFirst().errorCode()).isEqualTo("READ_TIMEOUT");
        assertThat(result.panels().getFirst().data()).isNull();
        assertThat(result.panels().get(1).status()).isEqualTo("ready");
    }
}
