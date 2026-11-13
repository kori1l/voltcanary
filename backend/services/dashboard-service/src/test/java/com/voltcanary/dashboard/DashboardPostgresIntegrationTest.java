package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.application.DashboardCatalog;
import com.voltcanary.dashboard.application.DashboardPublication;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DashboardPostgresIntegrationTest {
    @Test
    void reviewedDraftUpdateHasAtomicScopedReceiptAndRejectsStaleVersions() throws Exception {
        String owner = actor(), foreign = actor();
        String id = create(owner, "Before update").path("id").asText();
        var draft = request(get("/dashboards/" + id + "/draft"), owner, null, null, null, 200);
        var payload = body("Updated same dashboard").put("baseRevision", draft.path("baseRevision").asInt());
        String key = "reviewed-draft-" + UUID.randomUUID();
        var saved = request(put("/dashboards/" + id + "/draft"), owner, payload, key, etag(draft), 200);
        assertThat(saved.path("dashboardId").asText()).isEqualTo(id);
        assertThat(saved.path("etag")).isNotEqualTo(draft.path("etag"));
        var replay = request(put("/dashboards/" + id + "/draft"), owner, payload, key, etag(draft), 200);
        assertThat(replay).isEqualTo(saved);
        assertThat(request(get("/dashboards/" + id + "/draft/receipt"), owner, null, key, null, 200)).isEqualTo(saved);
        request(get("/dashboards/" + id + "/draft/receipt"), foreign, null, key, null, 404);
        request(put("/dashboards/" + id + "/draft"), owner, body("Conflict").put("baseRevision", 0), "different-key", etag(draft), 409);
        request(put("/dashboards/" + id + "/draft"), owner, body("Changed body").put("baseRevision", 0), key, etag(saved), 409);
        assertThat(request(get("/dashboards/" + id + "/draft"), owner, null, null, null, 200)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("select count(*) from dashboard_lifecycle_audit where dashboard_id=? and action='DRAFT_SAVED'", Long.class, UUID.fromString(id))).isEqualTo(1L);
    }
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired DashboardCatalog catalog;
    @Autowired DashboardPublication publication;
    @MockitoBean DashboardOwnership ownership;
    @MockitoBean com.voltcanary.dashboard.port.DashboardEvidenceReads evidenceReads;
    @MockitoBean com.voltcanary.dashboard.port.TemplateDeviceCatalogue templateCatalogue;
    @MockitoBean com.voltcanary.dashboard.sharing.DashboardWorkspaceAuthority workspaceAuthority;
    @Autowired com.voltcanary.dashboard.sharing.DashboardSharing sharing;
    private final ConcurrentHashMap<String, DashboardActor> actors = new ConcurrentHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deniedDevices = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void ownership() {
        when(ownership.resolve(any())).thenAnswer(invocation -> {
            String authorization = invocation.getArgument(0);
            if (authorization == null || !actors.containsKey(authorization)) {
                throw new DashboardFailure("UNAUTHENTICATED", "Authentication is required");
            }
            if (disabled.contains(authorization)) {
                throw new DashboardFailure("ACTOR_INACTIVE", "Active user is required");
            }
            return actors.get(authorization);
        });
        when(ownership.canReadDevice(anyString(), any(UUID.class), any(DashboardActor.class)))
                .thenAnswer(invocation -> !deniedDevices.contains(invocation.getArgument(1)));
        when(ownership.canReadLocation(anyString(), any(UUID.class), any(DashboardActor.class))).thenReturn(true);
        when(ownership.resolveReadOnly(any())).thenAnswer(invocation -> ownership.resolve(invocation.getArgument(0)));
        when(evidenceReads.scope(anyString(), any(), any())).thenAnswer(invocation -> {
            DashboardActor actor = invocation.getArgument(1);
            return new com.voltcanary.dashboard.port.DashboardEvidenceReads.ScopeSnapshot(actor.userId(), actor.accountId(),
                    0, 0, 0, List.of(), List.of(), false, java.time.Instant.now());
        });
        var emptyRead = mapper.createObjectNode().put("evaluatedCount", 0).put("deniedBindingCount", 0);
        emptyRead.putArray("authorizedDeviceIds");
        when(evidenceReads.telemetry(anyString(), any(), any())).thenReturn(emptyRead);
    }

    @Test
    void widgetCatalogAndSavedQueryReferencesAreAuthorizedAndReadOnly() throws Exception {
        String owner = actor(), foreign = actor();
        ObjectNode body = body("Query reference");
        var widgets = (com.fasterxml.jackson.databind.node.ArrayNode) body.path("definition").path("widgets");
        widgets.removeAll();
        widgets.addObject().put("id", "fleet").put("type", "fleet-count").put("typeVersion", 1).put("title", "Fleet");
        widgets.addObject().put("id", "future").put("type", "fleet-count").put("typeVersion", 99).put("title", "Future");
        String id = request(post("/dashboards"), owner, body, "query-create", null, 201).path("id").asText();
        var catalog = request(get("/dashboard-widget-types"), owner, null, null, null, 200);
        assertThat(catalog.size()).isEqualTo(18);
        assertThat(java.util.stream.StreamSupport.stream(catalog.spliterator(), false).filter(node -> node.path("supported").asBoolean()).count()).isEqualTo(18);
        var draft = request(get("/dashboards/" + id + "/draft"), owner, null, null, null, 200);
        var preview = request(post("/dashboards/" + id + "/query-validation"), owner,
                mapper.createObjectNode().put("draftEtag", draft.path("etag").asText()), null, null, 200);
        assertThat(preview.path("panels").get(0).path("status").asText()).isEqualTo("valid");
        assertThat(preview.path("panels").get(1).path("status").asText()).isEqualTo("unsupported");
        publish(owner, id, draft, "query-publish", 200);
        var queried = request(post("/dashboards/" + id + "/query"), owner, mapper.createObjectNode(), null, null, 200);
        assertThat(queried.path("panels").get(0).path("status").asText()).isEqualTo("no_data");
        assertThat(queried.path("panels").get(1).path("data").isNull()).isTrue();
        request(post("/dashboards/" + id + "/query"), foreign, mapper.createObjectNode(), null, null, 404);
        request(post("/dashboards/" + id + "/query"), owner, mapper.createObjectNode().put("endpoint", "arbitrary"), null, null, 422);
        request(post("/dashboards/" + id + "/query"), owner, mapper.createObjectNode().put("revision", 99), null, null, 404);
        request(post("/dashboards/" + id + "/query"), owner, mapper.createObjectNode().put("draftEtag", draft.path("etag").asText()), null, null, 409);
        assertThat(jdbc.queryForObject("select count(*) from dashboard_revisions where dashboard_id=?", Long.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void templatePrepareIsReadOnlyStrictScopedAndCreatesOnlyAnIdempotentPrivateDraft() throws Exception {
        String owner = actor(), foreign = actor();
        UUID device = UUID.randomUUID(), source = UUID.randomUUID();
        when(templateCatalogue.read(owner, device)).thenReturn(new com.voltcanary.dashboard.port.TemplateDeviceCatalogue.Device(
                device, "Authorized fridge", "FRIDGE", List.of(new com.voltcanary.dashboard.port.TemplateDeviceCatalogue.Metric("activePowerW", "W")),
                List.of(new com.voltcanary.dashboard.port.TemplateDeviceCatalogue.Source(source, "ordinary-source"))));
        ObjectNode intent = mapper.createObjectNode().put("name", "Prepared private draft").put("version", 1).put("period", "LAST_24_HOURS");
        intent.putArray("deviceIds").add(device.toString());
        intent.putArray("sourceIds").add(source.toString());
        long before = jdbc.queryForObject("select count(*) from dashboards", Long.class);
        var prepared = request(post("/dashboard-templates/my-devices/prepare"), owner, intent, null, null, 200);
        assertThat(prepared.path("status").asText()).isEqualTo("READY");
        assertThat(prepared.path("definition").path("widgets").size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from dashboards", Long.class)).isEqualTo(before);
        ObjectNode unsafe = intent.deepCopy(); unsafe.put("workspaceId", UUID.randomUUID().toString());
        request(post("/dashboard-templates/my-devices/prepare"), owner, unsafe, null, null, 422);
        var body = mapper.createObjectNode().set("definition", prepared.path("definition"));
        String key = "prepared-" + UUID.randomUUID();
        var created = request(post("/dashboards"), owner, body, key, null, 201);
        assertThat(created.path("publishedRevision").asInt()).isZero();
        var repeat = request(post("/dashboards"), owner, body, key, null, 201);
        assertThat(repeat.path("id").asText()).isEqualTo(created.path("id").asText());
        var receipt = request(get("/dashboards/creation-receipt"), owner, null, key, null, 200);
        assertThat(receipt.path("id").asText()).isEqualTo(created.path("id").asText());
        request(get("/dashboards/creation-receipt"), foreign, null, key, null, 404);
        request(get("/dashboards/creation-receipt"), owner, null, "missing-receipt", null, 404);
        request(get("/dashboards/" + created.path("id").asText()), foreign, null, null, null, 404);
        assertThat(jdbc.queryForObject("select count(*) from dashboard_revisions where dashboard_id=?", Long.class, UUID.fromString(created.path("id").asText()))).isZero();
        when(evidenceReads.scope(eq(owner), any(), any())).thenAnswer(invocation -> {
            DashboardActor actor = invocation.getArgument(1);
            var equipment = new com.voltcanary.dashboard.port.DashboardEvidenceReads.Device(device,
                    "Authorized fridge", "FRIDGE", null, "ACTIVE", 5L, "fixture", "SINGLE", java.time.Instant.EPOCH);
            return new com.voltcanary.dashboard.port.DashboardEvidenceReads.ScopeSnapshot(actor.userId(), actor.accountId(),
                    1, 1, deniedDevices.contains(device) ? 1 : 0, List.of(equipment), List.of(), false, java.time.Instant.now());
        });
        var zeroEvidence = mapper.createObjectNode().put("evaluatedCount", 1).put("deniedBindingCount", 0);
        zeroEvidence.putArray("authorizedDeviceIds").add(device.toString());
        zeroEvidence.putArray("registeredMetrics").addObject().put("key", "activePowerW").put("unit", "W");
        zeroEvidence.putArray("latest").addObject().put("metric", "activePowerW").put("unit", "W")
                .put("value", 0).put("timestamp", java.time.Instant.now().toString());
        when(evidenceReads.telemetry(eq(owner), any(), any())).thenReturn(zeroEvidence);
        String dashboardId = created.path("id").asText();
        var draft = request(get("/dashboards/" + dashboardId + "/draft"), owner, null, null, null, 200);
        ObjectNode query = mapper.createObjectNode().put("draftEtag", draft.path("etag").asText());
        query.putArray("widgetIds").add("template-0");
        var fresh = request(post("/dashboards/" + dashboardId + "/query"), owner, query, null, null, 200);
        assertThat(fresh.path("panels").get(0).path("data").path("value").path("value").asDouble(-1)).isZero();
        var cached = request(post("/dashboards/" + dashboardId + "/query"), owner, query, null, null, 200);
        assertThat(cached.path("cached").asBoolean()).isTrue();
        request(post("/dashboards/" + dashboardId + "/query"), foreign, query, null, null, 404);
        deniedDevices.add(device);
        var revoked = request(post("/dashboards/" + dashboardId + "/query"), owner, query, null, null, 200);
        assertThat(revoked.path("cached").asBoolean()).isFalse();
        assertThat(revoked.path("panels").get(0).path("status").asText()).isEqualTo("no_access");
        assertThat(revoked.path("panels").get(0).path("data").isNull()).isTrue();
        request(post("/dashboard-templates/my-devices/prepare"), owner, intent, null, null, 403);
        request(post("/dashboards"), owner, body, key, null, 422);
    }
    @Test void progressiveTransportAndSnapshotDetailsUseOneAuthorizedRevision() throws Exception {
        String owner=actor();
        var body=body("Progressive notes");
        var widgets=(com.fasterxml.jackson.databind.node.ArrayNode)body.path("definition").path("widgets");
        for(String name:List.of("first","second"))widgets.addObject().put("id",name).put("type","context-note").put("typeVersion",1).put("title",name)
                .putObject("payload").put("text","Recorded context "+name);
        String id=request(post("/dashboards"),owner,body,"progressive-create",null,201).path("id").asText();
        var draft=request(get("/dashboards/"+id+"/draft"),owner,null,null,null,200);
        publish(owner,id,draft,"progressive-publish",200);
        var async=mvc.perform(post("/dashboards/"+id+"/query-progressive").header("Authorization",owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        async.getAsyncResult(10000);
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(async)).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("application/x-ndjson");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var lines=response.getContentAsString().lines().map(line->{try{return mapper.readTree(line);}catch(Exception failure){throw new IllegalStateException(failure);}}).toList();
        assertThat(lines).hasSize(3);assertThat(lines.getLast().path("complete").asBoolean()).isTrue();
        assertThat(lines.get(0).path("asOf")).isEqualTo(lines.get(1).path("asOf"));
        String hash=lines.get(0).path("contextHash").asText();
        var details=request(get("/dashboards/"+id+"/query-snapshots/"+hash+"/panels/first"),owner,null,null,null,200);
        assertThat(details.path("data").path("text").asText()).isEqualTo("Recorded context first");
        disabled.add(owner);
        request(get("/dashboards/"+id+"/query-snapshots/"+hash+"/panels/first"),owner,null,null,null,403);
    }

    @Test
    void namedScreensSurvivePublicationCopyAndRollbackWhileDraftEditsStayPrivate() throws Exception {
        String owner = actor();ObjectNode body = body("Working screens");var definition = (ObjectNode)body.path("definition");
        definition.withArray("widgets").addObject().put("id", "note").put("type", "context-note").put("typeVersion", 1).put("title", "Context").putObject("payload").put("text", "Recorded context");
        var screens = definition.putArray("screens");screens.addObject().put("id", "monitoring").put("title", "Monitoring").putArray("widgetIds").add("note");
        screens.addObject().put("id", "analytics").put("title", "Analytics").putArray("widgetIds");screens.addObject().put("id", "incidents").put("title", "Incidents").putArray("widgetIds");
        String id = request(post("/dashboards"), owner, body, "screens-create", null, 201).path("id").asText();
        var draft = request(get("/dashboards/"+id+"/draft"),owner,null,null,null,200);publish(owner,id,draft,"screens-publish",200);
        var fresh = request(get("/dashboards/"+id+"/draft"),owner,null,null,null,200);
        var changed = fresh.path("definition").deepCopy();((ObjectNode)changed.path("screens").get(0)).put("title","Changed draft");
        var save = mapper.createObjectNode().put("baseRevision",1);save.set("definition",changed);
        var saved = request(put("/dashboards/"+id+"/draft"),owner,save,null,etag(fresh),200);
        var published = request(get("/dashboards/"+id),owner,null,null,null,200);
        assertThat(published.path("definition").path("screens").get(0).path("title").asText()).isEqualTo("Monitoring");
        String copy = request(post("/dashboards/"+id+"/copy"),owner,mapper.createObjectNode().put("name","Screen copy"),"screens-copy",null,201).path("id").asText();
        assertThat(request(get("/dashboards/"+copy+"/draft"),owner,null,null,null,200).path("definition").path("screens").size()).isEqualTo(3);
        var rollback = request(put("/dashboards/"+id+"/draft"),owner,mapper.createObjectNode().put("baseRevision",1).put("sourceRevision",1),null,etag(saved),200);
        assertThat(rollback.path("definition").path("screens").get(0).path("title").asText()).isEqualTo("Monitoring");
        request(get("/dashboards/"+id),actor(),null,null,null,404);
    }

    @Test
    void serverOwnershipPublicationPreferencesAndForeignHistoryAreIsolated() throws Exception {
        String owner = actor();
        String other = actor();
        ObjectNode body = body("Personal");
        body.put("ownerUserId", actors.get(other).userId().toString());
        body.put("accountId", actors.get(other).accountId().toString());
        JsonNode created = request(post("/dashboards"), owner, body, "create", null, 201);
        String id = created.path("id").asText();
        assertThat(created.path("ownerUserId").asText()).isEqualTo(actors.get(owner).userId().toString());
        assertThat(created.path("accountId").asText()).isEqualTo(actors.get(owner).accountId().toString());
        JsonNode draft = request(get("/dashboards/" + id + "/draft"), owner, null, null, null, 200);
        JsonNode published = publish(owner, id, draft, "publish", 200);
        assertThat(published.path("revision").asInt()).isEqualTo(1);
        JsonNode preferences = request(get("/dashboard-preferences/me"), owner, null, null, null, 200);
        ObjectNode preferenceBody = mapper.createObjectNode().put("defaultDashboardId", id);
        preferenceBody.putArray("favorites").add(id);
        preferenceBody.putObject("defaultContext");
        request(put("/dashboard-preferences/me"), owner, preferenceBody, null, etag(preferences), 200);
        assertThat(request(get("/dashboard-preferences/me"), owner, null, null, null, 200)
                .path("defaultDashboardId").asText()).isEqualTo(id);
        assertThat(request(get("/dashboards/" + id), owner, null, null, null, 200)
                .path("definition").path("name").asText()).isEqualTo("Personal");
        for (String suffix : List.of("", "/draft", "/revisions", "?revision=1")) {
            JsonNode error = request(get("/dashboards/" + id + suffix).header("X-User-Role", "ADMIN"),
                    other, null, null, null, 404);
            assertThat(error.path("message").asText()).isEqualTo("Dashboard not found");
        }
        request(post("/dashboards/" + id + "/copy"), other, mapper.createObjectNode().put("name", "Forbidden"), "copy", null, 404);
        request(get("/dashboards"), null, null, null, null, 401);
    }

    @Test
    void publishReplayConflictAndRollbackKeepImmutableHistory() throws Exception {
        String actor = actor();
        String id = create(actor, "History").path("id").asText();
        JsonNode original = request(get("/dashboards/" + id + "/draft"), actor, null, null, null, 200);
        JsonNode first = publish(actor, id, original, "one", 200);
        JsonNode replay = publish(actor, id, original, "one", 200);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_revisions WHERE dashboard_id=?", Long.class,
                UUID.fromString(id))).isEqualTo(1L);
        request(put("/dashboards/" + id + "/draft"), actor, body("Stale").put("baseRevision", 0), null, etag(original), 409);
        request(put("/dashboards/" + id + "/draft"), actor, body("Missing header").put("baseRevision", 1), null, null, 428);
        JsonNode current = request(get("/dashboards/" + id + "/draft"), actor, null, null, null, 200);
        request(put("/dashboards/" + id + "/draft"), actor, body("Old base").put("baseRevision", 0), null, etag(current), 409);
        JsonNode updated = request(put("/dashboards/" + id + "/draft"), actor,
                body("New name").put("baseRevision", 1), null, etag(current), 200);
        publish(actor, id, updated, "one", 409);
        publish(actor, id, updated, "two", 200);
        current = request(get("/dashboards/" + id + "/draft"), actor, null, null, null, 200);
        JsonNode rollback = request(put("/dashboards/" + id + "/draft"), actor,
                mapper.createObjectNode().put("baseRevision", 2).put("sourceRevision", 1), null, etag(current), 200);
        assertThat(publish(actor, id, rollback, "three", 200).path("revision").asInt()).isEqualTo(3);
        assertThat(request(get("/dashboards/" + id + "?revision=1"), actor, null, null, null, 200)
                .path("definition").path("name").asText()).isEqualTo("History");
        assertThat(request(get("/dashboards/" + id + "?revision=2"), actor, null, null, null, 200)
                .path("definition").path("name").asText()).isEqualTo("New name");
        assertThatThrownBy(() -> jdbc.update("UPDATE dashboard_revisions SET revision=99 WHERE dashboard_id=?",
                UUID.fromString(id))).hasMessageContaining("immutable");
        disabled.add(actor);
        publish(actor, id, original, "one", 403);
    }

    @Test
    void archiveInvalidatesDefaultRestoreKeepsIdAndSafeUnknownWidgetsCopy() throws Exception {
        String actor = actor();
        ObjectNode body = body("Unknown");
        UUID device = UUID.randomUUID();
        ObjectNode widget = mapper.createObjectNode().put("id", "future").put("type", "future-widget")
                .put("typeVersion", 99).put("title", "Preserved");
        widget.putObject("payload").put("futureFlag", true);
        widget.putObject("binding").putArray("deviceIds").add(device.toString());
        ((ObjectNode) body.path("definition")).putArray("widgets").add(widget);
        String id = request(post("/dashboards"), actor, body, "unknown", null, 201).path("id").asText();
        JsonNode draft = request(get("/dashboards/" + id + "/draft"), actor, null, null, null, 200);
        publish(actor, id, draft, "publish", 200);
        assertThat(request(get("/dashboards/" + id), actor, null, null, null, 200)
                .path("unsupportedWidgetIds").get(0).asText()).isEqualTo("future");
        JsonNode preference = request(get("/dashboard-preferences/me"), actor, null, null, null, 200);
        ObjectNode prefs = mapper.createObjectNode().put("defaultDashboardId", id);
        prefs.putArray("favorites").add(id);
        prefs.putObject("defaultContext");
        request(put("/dashboard-preferences/me"), actor, prefs, null, etag(preference), 200);
        draft = request(get("/dashboards/" + id + "/draft"), actor, null, null, null, 200);
        request(post("/dashboards/" + id + "/archive"), actor, null, null, etag(draft), 200);
        preference = request(get("/dashboard-preferences/me"), actor, null, null, null, 200);
        assertThat(preference.path("defaultDashboardId").isNull()).isTrue();
        assertThat(preference.path("fallbackReason").asText()).isEqualTo("DEFAULT_ARCHIVED");
        assertThat(preference.path("favorites").size()).isZero();
        draft = request(get("/dashboards/" + id + "/draft"), actor, null, null, null, 200);
        assertThat(request(post("/dashboards/" + id + "/restore"), actor, null, null, etag(draft), 200)
                .path("id").asText()).isEqualTo(id);
        deniedDevices.add(device);
        String copied = request(post("/dashboards/" + id + "/copy"), actor,
                mapper.createObjectNode().put("name", "Copied"), "copy", null, 201).path("id").asText();
        JsonNode copy = request(get("/dashboards/" + copied + "/draft"), actor, null, null, null, 200);
        assertThat(copy.path("definition").path("widgets").get(0).path("payload").path("futureFlag").asBoolean()).isTrue();
        assertThat(copy.path("definition").path("widgets").get(0).path("binding").path("mode").asText()).isEqualTo("UNBOUND");
        assertThat(copy.toString()).doesNotContain(device.toString());
        assertThat(copied).isNotEqualTo(id);
    }

    @Test
    void concurrentKeysQuotaCursorAndMigrationRerunAreSafe() throws Exception {
        String authorization = actor();
        DashboardActor actor = actors.get(authorization);
        ObjectNode body = body("Concurrent");
        DashboardDefinition definition = mapper.convertValue(body.path("definition"), DashboardDefinition.class);
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<UUID> operation = () -> catalog.create(actor, authorization, "same-key", body, definition).id();
            var results = executor.invokeAll(List.of(operation, operation, operation, operation));
            Set<UUID> ids = new java.util.HashSet<>();
            for (var result : results) {
                ids.add(result.get());
            }
            assertThat(ids).hasSize(1);
        }
        for (int index = 1; index < 10; index++) {
            create(authorization, "Dashboard " + index);
        }
        request(post("/dashboards"), authorization, body("Eleven"), "eleven", null, 422);
        JsonNode page = request(get("/dashboards?limit=3"), authorization, null, null, null, 200);
        assertThat(page.path("items").size()).isEqualTo(3);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        JsonNode next = request(get("/dashboards?limit=3&cursor=" + page.path("nextCursor").asText()),
                authorization, null, null, null, 200);
        assertThat(next.path("items").get(0).path("id").asText()).isNotEqualTo(page.path("items").get(0).path("id").asText());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboards WHERE owner_user_id=?", Long.class,
                actor.userId())).isEqualTo(10L);
        jdbc.update("UPDATE dashboard_idempotency SET expires_at=now()-interval '1 second' WHERE user_id=?", actor.userId());
        request(post("/dashboards"), authorization, body, "same-key", null, 422);
        String archived = page.path("items").get(0).path("id").asText();
        JsonNode draft = request(get("/dashboards/" + archived + "/draft"), authorization, null, null, null, 200);
        request(post("/dashboards/" + archived + "/archive"), authorization, null, null, etag(draft), 200);
        String renewed = request(post("/dashboards"), authorization, body, "same-key", null, 201).path("id").asText();
        assertThat(renewed).isNotEqualTo(archived);
    }

    @Test
    void concurrentPublishRetriesAppendOnlyOneRevision() throws Exception {
        String authorization = actor();
        String id = create(authorization, "Publish race").path("id").asText();
        JsonNode draft = request(get("/dashboards/" + id + "/draft"), authorization, null, null, null, 200);
        ObjectNode body = mapper.createObjectNode().put("draftEtag", draft.path("etag").asText());
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<Integer> operation = () -> publication.publish(actors.get(authorization), authorization,
                    UUID.fromString(id), etag(draft), "same-publication", body,
                    UUID.fromString(draft.path("etag").asText())).revision();
            for (var result : executor.invokeAll(List.of(operation, operation, operation, operation))) {
                assertThat(result.get()).isEqualTo(1);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_revisions WHERE dashboard_id=?",
                Long.class, UUID.fromString(id))).isEqualTo(1L);
    }

    private String actor() {
        String authorization = "Bearer test-" + UUID.randomUUID();
        actors.put(authorization, new DashboardActor(UUID.randomUUID(), UUID.randomUUID()));
        return authorization;
    }

    @Test
    void sharedAuthorDraftsKeepPublishSeparateAndRevocationIsImmediate() throws Exception {
        String owner=actor(),editor=actor(); UUID workspace=UUID.randomUUID();
        var ownerActor=actors.get(owner);var editorActor=actors.get(editor);
        when(workspaceAuthority.member(workspace,ownerActor.userId())).thenReturn(new com.voltcanary.common.security.WorkspaceAuthorization.Membership(workspace,ownerActor.userId(),"OWNER",1,List.of()));
        when(workspaceAuthority.member(workspace,editorActor.userId())).thenReturn(new com.voltcanary.common.security.WorkspaceAuthorization.Membership(workspace,editorActor.userId(),"MEMBER",1,List.of()));
        when(workspaceAuthority.resources(eq(workspace),eq(ownerActor.accountId()),any(),anyString())).thenAnswer(i->new com.voltcanary.common.security.WorkspaceAuthorization.Decision(workspace,ownerActor.accountId(),i.getArgument(2),i.getArgument(3),Set.of(),"current"));
        String id=create(owner,"Shared publication").path("id").asText();UUID dashboardId=UUID.fromString(id);
        var privateDraft=request(get("/dashboards/"+id+"/draft"),owner,null,null,null,200);publish(owner,id,privateDraft,"initial",200);
        sharing.attach(ownerActor,dashboardId,workspace,true);
        sharing.grants(ownerActor,dashboardId,List.of(new com.voltcanary.dashboard.sharing.DashboardAudienceRepository.Audience(editorActor.userId(),"USER",List.of("VIEW","EDIT"))),1,true);
        var ownerDraft=request(get("/dashboards/"+id+"/draft"),owner,null,null,null,200);
        var editorDraft=request(get("/dashboards/"+id+"/draft"),editor,null,null,null,200);
        assertThat(ownerDraft.path("etag").asText()).isNotEqualTo(editorDraft.path("etag").asText());
        var view=request(get("/dashboards/"+id),editor,null,null,null,200);
        assertThat(view.path("permissions").toString()).doesNotContain("publish");
        ObjectNode hiddenDefinition = (ObjectNode) body("Hidden GPS").path("definition");
        ObjectNode hiddenWidget = hiddenDefinition.withArray("widgets").addObject().put("id", "gps").put("type", "vehicle-map").put("typeVersion", 1).put("title", "Private car");
        String hiddenDeviceId = UUID.randomUUID().toString();
        hiddenWidget.putObject("binding").put("mode", "FIXED").putArray("deviceIds").add(hiddenDeviceId);
        hiddenWidget.putObject("query").put("sourceId", "private-gps-source").putObject("vehicleSources").put(hiddenDeviceId,"hidden-fleet-source");
        var hiddenView = sharing.viewerDefinition(editorActor, dashboardId, mapper.treeToValue(hiddenDefinition, com.voltcanary.dashboard.domain.DashboardDefinition.class));
        assertThat(mapper.valueToTree(hiddenView).path("widgets").get(0).path("query").has("sourceId")).isFalse();
        assertThat(mapper.valueToTree(hiddenView).path("widgets").get(0).path("query").path("vehicleSources").size()).isZero();
        publish(editor,id,editorDraft,"without-publish",403);
        sharing.grants(ownerActor,dashboardId,List.of(new com.voltcanary.dashboard.sharing.DashboardAudienceRepository.Audience(editorActor.userId(),"USER",List.of("VIEW","EDIT","PUBLISH"))),2,true);
        publish(editor,id,editorDraft,"editor-publication",200);
        publish(owner,id,ownerDraft,"stale-author-base",409);
        sharing.grants(ownerActor,dashboardId,List.of(),3,true);
        request(get("/dashboards/"+id),editor,null,null,null,403);
        request(post("/dashboards/"+id+"/query"),editor,mapper.createObjectNode(),null,null,403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_revisions WHERE dashboard_id=?",Long.class,dashboardId)).isEqualTo(2L);
    }

    private ObjectNode body(String name) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode definition = body.putObject("definition").put("schemaVersion", 1).put("name", name).put("description", "");
        definition.putObject("defaultContext");
        definition.putArray("sections");
        definition.putArray("widgets");
        definition.putObject("layouts");
        return body;
    }

    private JsonNode create(String actor, String name) throws Exception {
        return request(post("/dashboards"), actor, body(name), UUID.randomUUID().toString(), null, 201);
    }

    private JsonNode publish(String actor, String id, JsonNode draft, String key, int status) throws Exception {
        return request(post("/dashboards/" + id + "/publish"), actor,
                mapper.createObjectNode().put("draftEtag", draft.path("etag").asText()), key, etag(draft), status);
    }

    private String etag(JsonNode record) {
        return '"' + record.path("etag").asText() + '"';
    }

    private JsonNode request(MockHttpServletRequestBuilder request, String authorization, JsonNode body,
            String key, String ifMatch, int expectedStatus) throws Exception {
        if (authorization != null) request.header("Authorization", authorization);
        if (key != null) request.header("Idempotency-Key", key);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body.toString());
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expectedStatus);
        return mapper.readTree(response.getContentAsString());
    }
}
