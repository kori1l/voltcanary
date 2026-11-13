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
import static org.mockito.ArgumentMatchers.argThat;
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
        queries = new DashboardWidgetQueries(new com.voltcanary.dashboard.query.QueryDefinitionReader(new DashboardAccess(storage,null), storage), new WidgetManifests(),
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

    @Test void unsavedPreviewUsesNormalEvidenceAndRenewsAccessWithoutReadingOrWritingADashboard() {
        var definition = new DashboardDefinition(1, "Unsaved", null, mapper.createObjectNode(), List.of(),
                List.of(widget("fleet", "fleet-count", mapper.createObjectNode().put("mode", "ALL"))), mapper.createObjectNode());
        var preview = queries.preview("Bearer current", actor, definition);
        assertThat(preview.panels()).hasSize(1);
        assertThat(preview.panels().getFirst().status()).isEqualTo("ready");
        assertThat(preview.panels().getFirst().data().path("counts").path("registered").asInt()).isEqualTo(250);
        assertThat(preview.panels().getFirst().provenance().has("detailsPath")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(storage);
        denied = true;
        var revoked = queries.preview("Bearer current", actor, definition);
        assertThat(revoked.panels().getFirst().status()).isEqualTo("no_access");
        assertThat(revoked.panels().getFirst().data()).isNull();
    }

    @Test void fleetMapReadsAllVehiclesInOnePanelAndHonorsRuntimeScope() {
        devices.clear();
        var first=UUID.randomUUID(); var second=UUID.randomUUID();
        devices.add(new DashboardEvidenceReads.Device(first,"Tram A","VEHICLE",null,"ACTIVE",5L,"source","PERIODIC",Instant.EPOCH));
        devices.add(new DashboardEvidenceReads.Device(second,"Tram B","VEHICLE",null,"ACTIVE",5L,"source","PERIODIC",Instant.EPOCH));
        var binding=mapper.valueToTree(java.util.Map.of("mode","FIXED","deviceIds",List.of(first,second)));
        var query=mapper.valueToTree(java.util.Map.of("vehicleSources",java.util.Map.of(first,"gps-a",second,"gps-b")));
        definition(List.of(new Widget("fleet","vehicle-map",1,"Fleet",binding,query,null,null,null)));
        when(reads.positions(anyString(),any(),any())).thenAnswer(call -> {
            var read=(DashboardEvidenceReads.ReadRequest)call.getArgument(2);
            var result=evidence(read);
            var fleet=result.putObject("positions").putArray("fleet");
            read.deviceIds().forEach(device -> fleet.addObject().put("deviceId",device.toString()).putObject("position").put("recordedAt",Instant.now().toString()));
            return result;
        });
        var batch=queries.query("Bearer test",actor,id,request(false));
        assertThat(batch.panels()).hasSize(1);
        assertThat(batch.panels().getFirst().status()).isEqualTo("ready");
        assertThat(batch.panels().getFirst().data().path("positions").path("fleet").size()).isEqualTo(2);
        assertThat(batch.cached()).isFalse();
        var runtime=mapper.createObjectNode();
        runtime.putObject("scope").put("mode","FIXED").putArray("deviceIds").add(second.toString());
        var narrowed=queries.query("Bearer test",actor,id,new DashboardWidgetQueries.Request(null,null,runtime,false));
        assertThat(narrowed.panels().getFirst().evaluatedCount()).isEqualTo(1);
        verify(reads).positions(anyString(),any(),argThat(read -> read.deviceIds().equals(List.of(second))));
    }

    @Test void irrelevantPaginationSharesOneCountRead() {
        definition(java.util.stream.IntStream.range(0,24).mapToObj(index -> new Widget("count-"+index,"fleet-count",1,"Count",null,
                mapper.createObjectNode().put("rows",index+1).put("offset",index),null,null,null)).toList());
        var batch=queries.query("Bearer test",actor,id,request(true));
        assertThat(batch.panels()).hasSize(24).allSatisfy(panel -> assertThat(panel.status()).isEqualTo("ready"));
        verify(reads,times(1)).telemetry(anyString(),any(),any());
    }

    @Test void seriesReadsRetainRequiredAuthorizedDetailsWhileIgnoringUnusedDetails() {
        var device=devices.getFirst().id();
        var account=actor.accountId();
        var asOf=Instant.now();
        var frame=new com.voltcanary.dashboard.query.QueryTypes.Frame(asOf.minusSeconds(3600),asOf,asOf,"UTC",false,null,null,"NONE");
        var series=List.of(new com.voltcanary.dashboard.query.QueryTypes.Series(device,"activePowerW"));
        var request=new DashboardEvidenceReads.ReadRequest(List.of(device),account,frame,Set.of("activePowerW"),List.of(),10,0,"ACTIVE",series,false,null,Set.of("series"));
        var other=new DashboardEvidenceReads.ReadRequest(List.of(device),account,frame,Set.of("activePowerW"),List.of(device),25,10,"ACTIVE",series,false,null,Set.of("series"));
        var memo=new java.util.HashMap<QueryReadScheduler.Key,java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>();
        scheduler.read("telemetry",request,"Bearer a",actor,memo).join();
        scheduler.read("telemetry",other,"Bearer a",actor,memo).join();
        verify(reads,times(1)).telemetry(anyString(),any(),argThat(value -> value.detailDeviceIds().equals(List.of(device)) && value.series().equals(series)));
    }

    @Test void progressiveFastPanelDoesNotWaitForSlowOwner() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        var fast=new java.util.concurrent.CompletableFuture<com.voltcanary.dashboard.query.QueryTypes.Batch>();
        org.mockito.Mockito.doAnswer(call -> {
            release.await(3,java.util.concurrent.TimeUnit.SECONDS);
            var source=evidence(call.getArgument(2)); source.put("total",1);source.putArray("rows");return source;
        }).when(reads).incidents(anyString(),any(),any());
        definition(List.of(widget("slow","incident-list",null),widget("fast","fleet-count",null)));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result=executor.submit(() -> queries.query("Bearer test",actor,id,request(true),false,group -> {
                if(group.panels().stream().anyMatch(panel -> panel.widgetId().equals("fast")))fast.complete(group);
            }));
            try {
                var first=fast.get(2,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.isDone()).isFalse();
                assertThat(first.panels().getFirst().status()).isEqualTo("ready");
                release.countDown();
                var batch=result.get(3,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(batch.asOf()).isEqualTo(first.asOf());
                assertThat(batch.contextHash()).isEqualTo(first.contextHash());
            } finally {release.countDown();}
        }
    }

    @Test void detailsRenewAuthorizationAndMatchOriginalSnapshot() {
        definition(List.of(widget("count","fleet-count",null)));
        var wire=queries.query("Bearer test",actor,id,request(true));
        assertThat(wire.scopes()).hasSize(1);
        assertThat(wire.panels().getFirst().data().has("devices")).isFalse();
        assertThat(queries.snapshotDetails("Bearer test",actor,id,wire.contextHash(),"count").data().path("devices")).hasSize(250);
        denied=true;
        assertThatThrownBy(() -> queries.snapshotDetails("Bearer test",actor,id,wire.contextHash(),"count"))
                .isInstanceOf(DashboardFailure.class).hasMessageContaining("access changed");
    }
    @Test void businessCacheRevalidatesVersionAndForceRefresh() {
        var version=new java.util.concurrent.atomic.AtomicInteger(1);
        when(reads.businessVersion(anyString(),any(),any(),anyString())).thenAnswer(call -> mapper.createObjectNode().put("version",version.get()));
        when(reads.cost(anyString(),any(),any())).thenAnswer(call -> {
            var source=evidence(call.getArgument(2));source.putObject("estimate").put("amount",version.get()).put("coverage","COMPLETE");return source;
        });
        var binding=mapper.createObjectNode().put("mode","FIXED");binding.putArray("deviceIds").add(devices.getFirst().id().toString());
        definition(List.of(widget("cost","estimated-cost",binding)));
        assertThat(queries.query("Bearer test",actor,id,request(false)).cached()).isFalse();
        assertThat(queries.query("Bearer test",actor,id,request(false)).cached()).isTrue();
        verify(reads,times(1)).cost(anyString(),any(),any());
        version.incrementAndGet();
        var changed=queries.query("Bearer test",actor,id,request(false));
        assertThat(changed.cached()).isFalse();assertThat(changed.panels().getFirst().data().path("estimate").path("amount").asInt()).isEqualTo(2);
        assertThat(queries.query("Bearer test",actor,id,request(true)).cached()).isFalse();
        verify(reads,times(3)).cost(anyString(),any(),any());
        verify(reads,times(4)).businessVersion(anyString(),any(),any(),anyString());
    }

    @Test void explicitlyUnboundMeasurementsReturnNoDataWithoutOwnerEvidence() {
        definition(List.of(widget("missing", "latest-value", mapper.createObjectNode().put("mode", "UNBOUND"))));
        var result = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(result.panels().getFirst().status()).isEqualTo("no_data");
        assertThat(result.panels().getFirst().data()).isNull();
        verify(reads, never()).telemetry(anyString(), any(), any());
    }

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
    @Test void vehicleMapKeepsTypedSourceAndRefreshesWithoutSnapshotCache() {
        var car = new DashboardEvidenceReads.Device(UUID.randomUUID(), "Car", "VEHICLE", null, "ACTIVE", 5L, "SOURCE_CONFIG", "PERIODIC", Instant.EPOCH);
        devices.clear(); devices.add(car);
        var binding = mapper.createObjectNode().put("mode", "FIXED"); binding.putArray("deviceIds").add(car.id().toString());
        var config = mapper.createObjectNode().put("sourceId", "sim-car-001");
        definition(List.of(new Widget("map", "vehicle-map", 1, "Map", binding, config, null, null, null)));
        when(reads.positions(anyString(), any(), any())).thenAnswer(call -> {
            DashboardEvidenceReads.ReadRequest read = call.getArgument(2);
            assertThat(read.sourceId()).isEqualTo("sim-car-001");
            var evidence = evidence(read);
            var positions = evidence.putObject("positions");
            positions.putObject("latest").putObject("position").put("recordedAt", read.time().asOf().toString()).put("latitude", 44.8).put("longitude", 20.4);
            positions.putObject("history").putArray("points");
            return evidence;
        });
        var first = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(first.panels().getFirst().status()).isEqualTo("ready");
        assertThat(first.panels().getFirst().data().path("resultType").asText()).isEqualTo("POSITIONS");
        assertThat(queries.query("Bearer fixture", actor, id, request(false)).cached()).isFalse();
        verify(reads, times(2)).positions(anyString(), any(), any());
        var runtime = mapper.createObjectNode();
        runtime.putObject("scope").put("mode", "DYNAMIC").putArray("deviceIds").add(UUID.randomUUID().toString());
        var excluded = queries.query("Bearer fixture", actor, id, new DashboardWidgetQueries.Request(null, null, runtime, true));
        assertThat(excluded.panels().getFirst().status()).isEqualTo("no_data");
    }

    @Test void vehicleRangeLimitDoesNotFailOtherPanelsAndMissingSourceIsInvalid() {
        var car = new DashboardEvidenceReads.Device(UUID.randomUUID(), "Car", "VEHICLE", null, "ACTIVE", 5L, "SOURCE_CONFIG", "PERIODIC", Instant.EPOCH);
        devices.clear(); devices.add(car);
        var binding = mapper.createObjectNode().put("mode", "FIXED"); binding.putArray("deviceIds").add(car.id().toString());
        var config = mapper.createObjectNode().put("sourceId", "sim-car-001");
        config.putObject("time").put("mode", "RELATIVE").put("preset", "LAST_7_DAYS").put("timezone", "UTC");
        definition(List.of(new Widget("map", "vehicle-map", 1, "Map", binding, config, null, null, null), widget("fleet", "fleet-count", null)));
        var batch = queries.query("Bearer fixture", actor, id, request(true));
        assertThat(batch.panels()).extracting(panel -> panel.status()).containsExactly("invalid", "ready");
        assertThat(batch.panels().getFirst().errorCode()).isEqualTo("LIMIT_EXCEEDED");
        definition(List.of(widget("map", "vehicle-map", binding)));
        assertThat(queries.query("Bearer fixture", actor, id, request(true)).panels().getFirst().errorCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test void fixedWidgetRetainsOwnDeviceWhenRuntimeFilterChanges() {
        var fixed = mapper.createObjectNode().put("mode", "FIXED"); fixed.putArray("deviceIds").add(devices.getFirst().id().toString());
        definition(List.of(widget("kitchen", "fleet-count", fixed), widget("dynamic", "fleet-count", null)));
        var runtime = mapper.createObjectNode(); runtime.putObject("scope").put("mode", "DYNAMIC").putArray("deviceIds").add(devices.get(1).id().toString());
        var result = queries.query("Bearer fixture", actor, id, new DashboardWidgetQueries.Request(null, null, runtime, false));
        assertThat(result.scopes().get(result.panels().getFirst().data().path("scopeRef").asText()).path("devices").get(0).path("id").asText()).isEqualTo(devices.getFirst().id().toString());
        assertThat(result.scopes().get(result.panels().get(1).data().path("scopeRef").asText()).path("devices").get(0).path("id").asText()).isEqualTo(devices.get(1).id().toString());
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
        for (int index = 1; index <= 24; index++) widgets.add(new Widget("fleet" + index, "fleet-count", 1, "Fleet", mapper.createObjectNode().put("mode","FIXED").set("deviceIds",mapper.createArrayNode().add(devices.get(index).id().toString())),
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
    @Test void validatesDottedBooleanUsingOwnedMetadataWithoutReadingFacts() {
        UUID bus = devices.getFirst().id();
        String metric = "transport.doorsOpen";
        var binding = mapper.createObjectNode().put("mode", "FIXED");
        binding.putArray("deviceIds").add(bus.toString());
        var query = mapper.createObjectNode().put("unit", "boolean");
        query.putArray("series").addObject().put("deviceId", bus.toString()).put("metric", metric);
        definition(List.of(new Widget("doors", "time-series", 1, "Doors", binding, query, null, null, null)));
        org.mockito.Mockito.doAnswer(call -> {
            DashboardEvidenceReads.ReadRequest read = call.getArgument(2);
            var node = evidence(read);
            var metrics = node.putArray("registeredMetrics");
            if (read.deviceIds().contains(bus) && read.metrics().contains(metric))
                metrics.addObject().put("key", metric).put("unit", "boolean");
            return node;
        }).when(reads).telemetry(anyString(), any(), any());
        var validated = queries.query("Bearer fixture", actor, id, request(true), true);
        assertThat(validated.panels().getFirst().status()).isEqualTo("valid");
        verify(reads).telemetry(anyString(), any(), argThat(read -> read.deviceIds().equals(List.of(bus))
                && read.metrics().equals(Set.of(metric)) && read.projections().isEmpty()));
    }

    @Test void vehicleFieldWidgetsShareAuthorizedSourceReadAndRetainRecordedHistoryWithoutLatest() {
        var car = new DashboardEvidenceReads.Device(UUID.randomUUID(), "Bus", "VEHICLE", null, "ACTIVE", 5L, "SOURCE_CONFIG", "PERIODIC", Instant.EPOCH);
        devices.clear(); devices.add(car);
        var binding = mapper.createObjectNode().put("mode", "FIXED"); binding.putArray("deviceIds").add(car.id().toString());
        var config = mapper.createObjectNode().put("sourceId", "bus-gps");
        var presentation = mapper.createObjectNode().put("vehicleField", "transport.doorsOpen");
        definition(List.of(new Widget("doors", "vehicle-value", 1, "Doors", binding, config, presentation, null, null),
                new Widget("history", "vehicle-timeline", 1, "History", binding, config, presentation, null, null)));
        when(reads.positions(anyString(), any(), any())).thenAnswer(call -> {
            DashboardEvidenceReads.ReadRequest read = call.getArgument(2);
            assertThat(read.sourceId()).isEqualTo("bus-gps");
            assertThat(read.deviceIds()).containsExactly(car.id());
            var result = evidence(read);
            var positions = result.putObject("positions");
            positions.putObject("latest").putNull("position");
            positions.putObject("history").putArray("points").addObject().put("recordedAt", read.time().from().toString())
                    .putObject("fields").putObject("transport.doorsOpen").put("value", false);
            return result;
        });
        var batch = queries.query("Bearer fixture", actor, id, request(false));
        assertThat(batch.panels()).extracting(panel -> panel.status()).containsExactly("no_data", "ready");
        assertThat(batch.panels().get(1).data().path("positions").path("history").path("points").size()).isEqualTo(1);
        assertThat(batch.cached()).isFalse();
        verify(reads, times(1)).positions(anyString(), any(), any());
    }
}
