package com.voltcanary.device.configurable;

import static org.junit.jupiter.api.Assertions.*;

import com.voltcanary.device.configurable.adapter.JdbcNativeHistoryLeases;
import com.voltcanary.device.configurable.adapter.JdbcNativeHistoryRows;
import com.voltcanary.device.configurable.adapter.NativeGenerationRetention;
import com.voltcanary.device.configurable.adapter.NativeQueryCursor;
import com.voltcanary.device.configurable.adapter.NativeSqlLayout;
import com.voltcanary.vcl.VclException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.junit.jupiter.api.Tag("docker")
class NativeImportsPostgresTest extends NativeImportsContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource source;

    @BeforeAll
    static void setup() {
        source = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        new JdbcTemplate(source).execute(
            "CREATE TABLE accounts(id UUID PRIMARY KEY,user_id UUID NOT NULL,status VARCHAR(32) NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        );
        NativeTestMigrations.apply(source);
    }

    @Override
    DataSource source() {
        return source;
    }

    @Test
    void postgresRetentionPreservesPinnedGenerationsThenReclaimsOnlyTheRetiredTables() {
        var fixture = fixture(true);
        insert(fixture, "retired value");
        var retired = head(fixture);
        var dataset = retired.schema().id();
        var document = parser.parse(query(fixture, 10, null).toString());
        queries(clock).seal(fixture.account(), fixture.actor(), document);
        String key = UUID.randomUUID().toString();
        var prepared = validate(
            fixture,
            document(fixture, "name,amount\nactive value,1.5\n", "CSV", "STAGED_REPLACE"),
            key
        );
        assertEquals("SUCCEEDED", applyImport(fixture, prepared, key).status());
        var layout = new NativeSqlLayout(retired);
        sql().update(
            "UPDATE cfg_native_generation SET retired_at=? WHERE dataset_id=? AND generation=?",
            clock.instant().minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC),
            dataset,
            retired.generation()
        );

        var grace = Duration.ofDays(1);
        new NativeGenerationRetention(source, clock, grace).reclaimExpired();
        assertTrue(relationExists(layout.table()));
        assertTrue(relationExists(layout.historyTable()));

        var later = Clock.offset(clock, Duration.ofMinutes(16));
        var leases = new JdbcNativeHistoryLeases(source, later);
        var account = fixture.account();
        var actor = fixture.actor();
        var lease = leases.acquire(
            account,
            actor,
            dataset,
            retired.generation(),
            retired.dataVersion(),
            300,
            key + "-lease"
        );
        var retention = new NativeGenerationRetention(source, later, grace);
        retention.reclaimExpired();
        assertTrue(relationExists(layout.table()));
        assertTrue(relationExists(layout.historyTable()));

        assertEquals("RELEASED", leases.release(account, actor, dataset, lease.id()).status());
        retention.reclaimExpired();
        assertFalse(relationExists(layout.table()));
        assertFalse(relationExists(layout.historyTable()));
        var history = new JdbcNativeHistoryRows(
            source,
            new NativeQueryCursor(new byte[32], later),
            null
        );
        assertEquals(
            "HISTORY_GENERATION_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                history.query(account, actor, retired.generation(), retired.dataVersion(), document)
            ).code()
        );
        var live = queries(later).query(account, actor, document);
        assertEquals(
            "active value",
            live.rows().getFirst().values().get(NativeFixtures.TEXT).path("value").asText()
        );
    }

    private boolean relationExists(String name) {
        return Boolean.TRUE.equals(
            sql().queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name)
        );
    }
}
