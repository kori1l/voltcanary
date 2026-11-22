package com.voltcanary.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.query.CompactDashboardEvidence;
import com.voltcanary.dashboard.query.DashboardWidgetQueries;
import com.voltcanary.dashboard.query.QueryTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompactDashboardEvidenceTest {

    @Test
    void expiredOrForeignSnapshotsNeverReadOrExposeOwnerInformation() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var actor = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
        var now = Instant.now();
        var id = UUID.randomUUID();
        var panel = new QueryTypes.Panel(
            "note",
            "ready",
            mapper
                .createObjectNode()
                .put("widgetType", "context-note")
                .put("text", "Private information"),
            "expired",
            null,
            0,
            0,
            now,
            now,
            mapper.createObjectNode(),
            null,
            null
        );
        var compact = new CompactDashboardEvidence(mapper);
        var request = new DashboardWidgetQueries.Request(1, null, null, false);
        var queries = org.mockito.Mockito.mock(DashboardWidgetQueries.class);
        compact.compact(
            actor,
            request,
            new QueryTypes.Batch(
                id,
                1,
                null,
                "expired",
                now.minusSeconds(31),
                now,
                false,
                List.of(panel)
            )
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            compact.details("Bearer test", actor, id, "expired", "note", queries)
        )
            .isInstanceOf(com.voltcanary.dashboard.domain.DashboardFailure.class)
            .hasMessageContaining("Refresh");
        compact.compact(
            actor,
            request,
            new QueryTypes.Batch(id, 1, null, "current", now, now, false, List.of(panel))
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            compact.details(
                "Bearer foreign",
                new DashboardActor(UUID.randomUUID(), UUID.randomUUID()),
                id,
                "current",
                "note",
                queries
            )
        ).isInstanceOf(com.voltcanary.dashboard.domain.DashboardFailure.class);
        org.mockito.Mockito.verifyNoInteractions(queries);
    }

    @Test
    void primarySeriesAndScopesAreTransmittedOnce() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var data = mapper.createObjectNode().put("widgetType", "time-series");
        var series = data.putArray("series");
        for (int i = 0; i < 200; i++) series
            .addObject()
            .put("index", i)
            .put("minimum", i)
            .put("maximum", i + 2)
            .put("mean", i + 1)
            .put("gapCount", 0);
        data.putArray("devices").addObject().put("id", "device").put("name", "Equipment");
        data.putArray("locations");
        ObjectNode source = data.putObject("sources").putObject("telemetry");
        source.set("series", series);
        source.put("asOf", Instant.now().toString());
        source.putObject("measurementQualityCounts").put("OK", 200);
        var now = Instant.now();
        var panels = java.util.stream.IntStream.range(0, 24)
            .mapToObj(i ->
                new QueryTypes.Panel(
                    "w" + i,
                    "ready",
                    data,
                    "context",
                    null,
                    1,
                    1,
                    now,
                    now,
                    mapper.createObjectNode(),
                    null,
                    null
                )
            )
            .toList();
        var batch = new QueryTypes.Batch(
            UUID.randomUUID(),
            1,
            null,
            "context",
            now,
            now,
            false,
            panels
        );
        var compact = new CompactDashboardEvidence(mapper).compact(
            new DashboardActor(UUID.randomUUID(), UUID.randomUUID()),
            new DashboardWidgetQueries.Request(1, null, null, false),
            batch
        );
        int before = mapper.writeValueAsBytes(batch).length,
            after = mapper.writeValueAsBytes(compact).length;
        System.out.println("Compact fixture raw bytes: " + before + " -> " + after);
        assertThat(after).isLessThan((int) (before * 0.6));
        assertThat(compact.scopes()).hasSize(1);
        assertThat(compact.panels().getFirst().data().path("series")).isEqualTo(series);
        assertThat(
            compact
                .panels()
                .getFirst()
                .data()
                .path("sources")
                .path("telemetry")
                .path("measurementQualityCounts")
                .path("OK")
                .asInt()
        ).isEqualTo(200);
        assertThat(
            compact.panels().getFirst().data().path("sources").path("telemetry").has("series")
        ).isFalse();
    }
}
