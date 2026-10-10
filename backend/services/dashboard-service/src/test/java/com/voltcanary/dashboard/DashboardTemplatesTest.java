package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.controller.DashboardTemplateController;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.query.QueryTimeResolver;
import com.voltcanary.dashboard.query.QueryTypes;
import com.voltcanary.dashboard.query.WidgetManifests;
import com.voltcanary.dashboard.templates.DashboardTaskTemplates;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DashboardTemplatesTest {
    @Test void catalogIsSymbolicVersionedAndEnergyGated() throws Exception {
        var catalog = new DashboardTaskTemplates(new WidgetManifests()).catalog();
        assertThat(catalog).hasSize(5);
        assertThat(catalog.stream().filter(item -> item.available()).toList()).hasSize(5);
        assertThat(catalog.stream().filter(item -> item.id().equals("energy")).findFirst().orElseThrow().available()).isTrue();
        String wire = new ObjectMapper().writeValueAsString(catalog);
        assertThat(wire).doesNotContain("ownerUserId", "accountId", "deviceId", "token", "password");
        assertThat(wire).contains("temperatureC", "NEXT_7_DAYS", "primary", "equipment", "location");
        assertThat(catalog.getFirst().seriesHours()).isEqualTo(6);
    }
    @Test void routeRenewsOwnershipAndSixHourWindowMovesWithAsOf() {
        var ownership = mock(DashboardOwnership.class);
        new DashboardTemplateController(ownership, new DashboardTaskTemplates(new WidgetManifests())).list("Bearer current");
        verify(ownership).resolve("Bearer current");
        Instant first = Instant.parse("2026-10-09T00:00:00Z");
        var time = new QueryTypes.Time(QueryTypes.TimeMode.RELATIVE, QueryTypes.Preset.LAST_6_HOURS, "UTC", null, null);
        var resolver = new QueryTimeResolver();
        var frame = resolver.resolve(time, null, null, first);
        assertThat(Duration.between(frame.from(), frame.to())).isEqualTo(Duration.ofHours(6));
        assertThat(resolver.resolve(time, null, null, first.plusSeconds(60)).from()).isEqualTo(frame.from().plusSeconds(60));
    }
}
