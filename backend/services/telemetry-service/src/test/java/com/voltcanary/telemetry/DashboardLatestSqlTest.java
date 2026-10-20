package com.voltcanary.telemetry;

import com.voltcanary.telemetry.repository.LatestTelemetrySql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DashboardLatestSqlTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static final UUID device=UUID.randomUUID(),account=UUID.randomUUID(),foreign=UUID.randomUUID();
    static final Instant asOf=Instant.parse("2026-10-11T00:00:00Z");
    @BeforeAll static void fixture() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        jdbc.execute("CREATE SCHEMA telemetry");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        jdbc.execute("CREATE TABLE telemetry.measurements(device_id uuid,account_id uuid,metric_name text,unit text,metric_value double precision,source_id text,recorded_at timestamp NOT NULL,received_at timestamp,quality text)");
        jdbc.execute("SELECT create_hypertable('telemetry.measurements','recorded_at',chunk_time_interval => INTERVAL '1 day')");
        jdbc.execute("CREATE INDEX idx_latest ON telemetry.measurements(device_id,metric_name,account_id,recorded_at DESC,source_id DESC) INCLUDE(unit,metric_value,received_at,quality) WHERE metric_name<>'_legacy' AND metric_value BETWEEN -1e308 AND 1e308");
        jdbc.update("INSERT INTO telemetry.measurements SELECT ?,?, 'metric-'||(n%8),'W',n,'source',?::timestamp-n*interval '1 second',?,'OK' FROM generate_series(1,121362)n",device,account,Timestamp.from(asOf),Timestamp.from(asOf));
        insert("old-observed",account,asOf.minusSeconds(86400*400),42,"OK","a");
        insert("future-only",account,asOf.plusSeconds(60),1,"OK","a");
        insert("foreign-only",foreign,asOf.minusSeconds(1),1,"OK","a");
        insert("null-account",null,asOf.minusSeconds(1),2,"SUSPECT","a");
        insert("metric-0",null,asOf.minusSeconds(1),3,"SUSPECT","z");
        insert("metric-0",account,asOf.minusSeconds(1),4,"OK","zz");
        insert("metric-1",account,asOf,Double.NaN,"OK","zz");
        insert("_legacy",account,asOf,99,"OK","a");
        jdbc.execute("ANALYZE telemetry.measurements");
    }
    static void insert(String metric,UUID owner,Instant time,double value,String quality,String source) {
        jdbc.update("INSERT INTO telemetry.measurements VALUES(?,?,?,'W',?,?,?,? ,?)",device,owner,metric,value,source,Timestamp.from(time),Timestamp.from(time),quality);
    }
    String legacy() {return "SELECT DISTINCT ON(device_id,metric_name) device_id,metric_name,unit,metric_value,source_id,recorded_at,received_at,quality FROM telemetry.measurements WHERE device_id=? AND (account_id=? OR account_id IS NULL) AND recorded_at<=? AND metric_name<>'_legacy' AND metric_value BETWEEN -1e308 AND 1e308 ORDER BY device_id,metric_name,recorded_at DESC,source_id DESC";}
    @Test void exactHistoricalAndTenantParityAndNoDiskSort() throws Exception {
        var args=new Object[]{device,account,Timestamp.from(asOf)};
        var expected=jdbc.queryForList(legacy(),args);
        var actual=jdbc.queryForList(LatestTelemetrySql.forDevices(1),args);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).anySatisfy(row -> {assertThat(row.get("metric_name")).isEqualTo("old-observed");assertThat(row.get("metric_value")).isEqualTo(42.0);});
        String difference="WITH old AS ("+legacy()+"),new AS ("+LatestTelemetrySql.forDevices(1)+") SELECT count(*) FROM ((SELECT * FROM old EXCEPT SELECT * FROM new) UNION ALL (SELECT * FROM new EXCEPT SELECT * FROM old))delta";
        assertThat(jdbc.queryForObject(difference,Long.class,device,account,Timestamp.from(asOf),device,account,Timestamp.from(asOf))).isZero();
        for(int i=0;i<3;i++) {
            String plan=jdbc.queryForObject("EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) "+LatestTelemetrySql.forDevices(1),String.class,args);
            assertThat(plan).doesNotContain("external merge").doesNotContain("\"Sort Space Type\": \"Disk\"");
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan).get(0);
            assertThat(plan).contains("Index Only Scan");
            assertThat(sequentialRows(json)).isLessThan(1000);
            String baseline=jdbc.queryForObject("EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) "+legacy(),String.class,args);
            var old=new com.fasterxml.jackson.databind.ObjectMapper().readTree(baseline).get(0);
            System.out.println("Latest plan run"+i+": legacy="+old.path("Execution Time")+"ms optimized="+json.path("Execution Time")+"ms; returned="+actual.size()+"; rows="+121370);
        }
    }
    private long sequentialRows(com.fasterxml.jackson.databind.JsonNode node) {
        long count="Seq Scan".equals(node.path("Node Type").asText())
                ? (node.path("Actual Rows").asLong()+node.path("Rows Removed by Filter").asLong())*node.path("Actual Loops").asLong():0;
        for(var child:node)if(child.isContainerNode())count+=sequentialRows(child);
        return count;
    }
    @Test void seriesProjectionDoesNotExecuteLatestOrSummaryOrQualityReads() {
        var mock=org.mockito.Mockito.mock(JdbcTemplate.class);
        var repository=new com.voltcanary.telemetry.repository.DashboardTelemetryRepository(mock,new com.fasterxml.jackson.databind.ObjectMapper(),new com.voltcanary.telemetry.service.MetricCatalog(),org.mockito.Mockito.mock(com.voltcanary.telemetry.variable.VariableDashboardSeries.class));
        var request=new com.voltcanary.common.web.DashboardOwnerReadRequest(UUID.randomUUID(),account,List.of(device),
                new com.voltcanary.common.web.DashboardOwnerReadRequest.TimeFrame(asOf.minusSeconds(3600),asOf,asOf,null,null),java.util.Set.of("activePowerW"),List.of(),1,0,"ACTIVE",
                List.of(new com.voltcanary.common.web.DashboardOwnerReadRequest.Series(device,"activePowerW")),null,java.util.Set.of("series"));
        var result=repository.read(request,List.of(device));
        var calls=org.mockito.Mockito.mockingDetails(mock).getInvocations().stream().filter(call -> call.getMethod().getName().equals("query")).toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().getArgument(0,String.class)).contains("WITH raw AS");
        assertThat(result.path("latest")).isEmpty();assertThat(result.path("summaries")).isEmpty();
    }
    @Test void discoversOwnedDottedBooleanMetricsAndReturnsMeasuredStatesOnly() {
        UUID bus=UUID.randomUUID();
        String metric="transport.doorsOpen";
        for (int i=0;i<2;i++) jdbc.update("INSERT INTO telemetry.measurements VALUES(?,?,?,'boolean',?,'bus',?,?,'OK')",
                bus,account,metric,i,Timestamp.from(asOf.minusSeconds(10-i*5)),Timestamp.from(asOf));
        jdbc.update("INSERT INTO telemetry.measurements VALUES(?,?,?,'boolean',1,'foreign',?,?,'OK')",
                bus,foreign,metric,Timestamp.from(asOf.minusSeconds(1)),Timestamp.from(asOf));
        var request=new com.voltcanary.common.web.DashboardOwnerReadRequest(UUID.randomUUID(),account,List.of(bus),
                new com.voltcanary.common.web.DashboardOwnerReadRequest.TimeFrame(asOf.minusSeconds(60),asOf,asOf,null,null),
                java.util.Set.of(metric),List.of(bus),20,0,null,List.of(new com.voltcanary.common.web.DashboardOwnerReadRequest.Series(bus,metric)),null,java.util.Set.of("series"));
        var repository=new com.voltcanary.telemetry.repository.DashboardTelemetryRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),new com.voltcanary.telemetry.service.MetricCatalog(),org.mockito.Mockito.mock(com.voltcanary.telemetry.variable.VariableDashboardSeries.class));
        var result=repository.read(request,List.of(bus));
        assertThat(result.path("registeredMetrics").toString()).contains(metric).contains("boolean");
        var series=result.path("series").get(0);
        assertThat(series.path("mode").asText()).isEqualTo("MEASURED_STATES");
        assertThat(series.path("points").size()).isEqualTo(2);
        assertThat(series.path("points").get(0).path("value").asDouble()).isZero();
        assertThat(series.path("points").get(1).path("value").asDouble()).isEqualTo(1);
    }
}
