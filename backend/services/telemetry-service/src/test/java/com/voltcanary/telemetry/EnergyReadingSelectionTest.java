package com.voltcanary.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.web.DashboardEnergyBatchReadRequest;
import com.voltcanary.common.web.DashboardEnergyReadRequest;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import com.voltcanary.telemetry.reference.LegacyEnergyPeriodCalculator;
import com.voltcanary.telemetry.repository.EnergyReadingRepository;
import com.voltcanary.telemetry.repository.EnergyReadingRepository.StoredReading;
import com.voltcanary.telemetry.repository.EnergyReadingRepository.Range;
import com.voltcanary.telemetry.service.EnergyPeriodReadService;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

@Testcontainers
class EnergyReadingSelectionTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));
    static final Instant base = Instant.parse("2026-10-01T00:00:00Z");
    static final UUID account=UUID.randomUUID(), user=UUID.randomUUID(), device=UUID.randomUUID(), missing=UUID.randomUUID();
    static final UUID firstPhysical=UUID.randomUUID(), replacement=UUID.randomUUID();
    static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    static final AtomicInteger selects = new AtomicInteger();
    static JdbcTemplate jdbc;
    static EnergyReadingRepository repository;
    static EnergyPeriodReadService service;

    @BeforeAll static void fixture() throws Exception {
        var datasource = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc = new JdbcTemplate(new AbstractDataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException { return counted(datasource.getConnection()); }
            @Override public Connection getConnection(String username,String password) throws java.sql.SQLException {
                return counted(datasource.getConnection(username,password));
            }
        });
        jdbc.execute("CREATE SCHEMA telemetry");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        jdbc.execute("CREATE TABLE telemetry.measurements(device_id uuid,account_id uuid,metric_name text,unit text,metric_value double precision,source_id text,recorded_at timestamp NOT NULL,quality text)");
        jdbc.execute("SELECT create_hypertable('telemetry.measurements','recorded_at',chunk_time_interval => INTERVAL '1 day')");
        try (var stream=EnergyReadingSelectionTest.class.getResourceAsStream("/db/vendor/postgresql/V009__energy_canonical_stream_index.sql")) {
            jdbc.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        jdbc.update("""
                INSERT INTO telemetry.measurements SELECT ?,?,m.metric,m.unit,
                CASE WHEN m.metric='energyWh' THEN (n%120)*10 ELSE 400+n END,'source',?::timestamp+n*interval '1 minute',
                CASE WHEN n%37=0 THEN 'SUSPECT' ELSE 'OK' END
                FROM generate_series(-60,480)n CROSS JOIN (VALUES('energyWh','Wh'),('activePowerW','W'))m(metric,unit)
                WHERE n NOT BETWEEN 80 AND 110
                """,device,account,Timestamp.from(base));
        jdbc.update("""
                INSERT INTO telemetry.measurements SELECT ?,?,m.metric,m.unit,n,'aux',?::timestamp+n*interval '1 minute','OK'
                FROM generate_series(0,480)n CROSS JOIN (VALUES('energyWh','Wh'),('activePowerW','W'))m(metric,unit)
                """,device,account,Timestamp.from(base));
        jdbc.update("INSERT INTO telemetry.measurements SELECT ?,?,'activePowerW','W',n,'unrelated',?::timestamp+n*interval '0.1 second','OK' FROM generate_series(1,100000)n",
                device,account,Timestamp.from(base));
        insert(account,46,9999,"source","OK","Wh");
        insert(null,46,8888,"source","OK","Wh");
        insert(null,45,450,"source","OK","Wh");
        insert(account,60,Double.NaN,"source","OK","Wh");
        insert(account,61,-1,"source","OK","Wh");
        insert(account,62,62,"source","OK","unknown");
        insert(account,63,0.63,"source","OK","kWh");
        insert(UUID.randomUUID(),64,999999,"source","OK","Wh");
        insert(account,600,999999,"source","OK","Wh");
        jdbc.execute("VACUUM ANALYZE telemetry.measurements");
        repository=spy(new EnergyReadingRepository(jdbc));
        service=new EnergyPeriodReadService(repository,mapper);
    }

    private static Connection counted(Connection connection) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) -> {
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                    && sql.startsWith("SELECT") && sql.contains("telemetry.measurements")) selects.incrementAndGet();
            try { return method.invoke(connection,args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    private static void insert(UUID owner,int minute,double value,String source,String quality,String unit) {
        jdbc.update("INSERT INTO telemetry.measurements VALUES(?,?,'energyWh',?,?,?, ?,?)",device,owner,unit,value,source,
                Timestamp.from(base.plusSeconds(minute*60L)),quality);
    }

    private List<EnergyMeterDefinition> history() {
        var events=List.of(new EnergyMeterDefinition.CounterEvent(base.plusSeconds(7200),"RESET",null),
                new EnergyMeterDefinition.CounterEvent(base.plusSeconds(14400),"ROLLOVER",1200.0));
        return List.of(new EnergyMeterDefinition(1,firstPhysical,null,"source","COUNTER_DELTA",900,base.minusSeconds(172800),base.plusSeconds(5700),events),
                new EnergyMeterDefinition(2,firstPhysical,null,"source","COUNTER_DELTA",1200,base.plusSeconds(5700),base.plusSeconds(10800),events),
                new EnergyMeterDefinition(3,replacement,null,"source","COUNTER_DELTA",900,base.plusSeconds(10800),base.plusSeconds(21600),List.of()),
                new EnergyMeterDefinition(4,UUID.nameUUIDFromBytes("aux".getBytes()),null,"aux","POWER_INTEGRAL",900,base.plusSeconds(21600),null,List.of()));
    }

    private JsonNode scope() {
        var scope=mapper.createObjectNode();
        var devices=scope.putArray("devices");
        devices.addObject().put("id",device.toString()).put("name","Meter").set("energyMeters",mapper.valueToTree(history()));
        devices.addObject().put("id",missing.toString()).put("name","No meter").putArray("energyMeters");
        return scope;
    }

    private DashboardOwnerReadRequest request(Instant from,Instant to,Instant compareFrom,Instant compareTo) {
        return new DashboardOwnerReadRequest(user,account,List.of(device,missing),
                new DashboardOwnerReadRequest.TimeFrame(from,to,base.plusSeconds(28800),compareFrom,compareTo),Set.of(),List.of(),1,0,"ACTIVE",List.of());
    }

    private List<StoredReading> broad() {
        return jdbc.query("SELECT * FROM telemetry.measurements WHERE device_id=? AND (account_id=? OR account_id IS NULL) ORDER BY recorded_at,metric_name,source_id",
                (row,index) -> new StoredReading(row.getString("metric_name"),new Reading(row.getTimestamp("recorded_at").toInstant(),row.getDouble("metric_value"),
                        row.getString("unit"),row.getString("source_id"),row.getString("quality"))),device,account);
    }

    @Test void oneSelectFor1And12And32CyclesAndExactLegacyEpochEvidence() throws Exception {
        var broad=broad();
        for (int count:List.of(1,12,32)) {
            var intervals=java.util.stream.IntStream.range(0,count).mapToObj(index -> new DashboardEnergyBatchReadRequest.Interval(
                    "cycle-"+index,List.of(device,missing),base.plusSeconds(index*850L),base.plusSeconds(index*850L+1600),true)).toList();
            selects.set(0);
            clearInvocations(repository);
            var result=service.readBatch(new DashboardEnergyBatchReadRequest(request(base,base.plusSeconds(28800),null,null),intervals),scope());
            assertThat(selects.get()).isEqualTo(1);
            @SuppressWarnings("unchecked") ArgumentCaptor<List<Range>> captor=ArgumentCaptor.forClass(List.class);
            verify(repository).readSelected(any(),captor.capture());
            var rows=repository.readSelected(account,captor.getValue()).get(device);
            assertThat(rows).hasSizeLessThan(1600).noneMatch(sample -> sample.reading().sourceId().equals("unrelated"))
                    .noneMatch(sample -> sample.metric().equals("energyWh") && sample.reading().sourceId().equals("aux"));
            for (var interval:intervals) {
                var period=result.path("intervals").path(interval.key()).path("period");
                assertLegacy(period,interval.from(),interval.to(),interval.from().minusSeconds(86400),base.plusSeconds(28800),broad);
                assertThat(period.path("evaluatedDevices").asInt()).isEqualTo(2);
                assertThat(period.path("devices").get(1).path("energyKWh").isNull()).isTrue();
            }
            var query=EnergyReadingRepository.selectedQuery(account,captor.getValue());
            String plan=jdbc.queryForObject("EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) "+query.sql(),String.class,query.parameters().toArray());
            assertThat(plan).contains("idx_measurements_energy_stream").doesNotContain("external merge");
            assertThat(scannedRows(mapper.readTree(plan).get(0))).isLessThan(2000);
            System.out.println(count+" cycles: one SELECT; broad rows="+broad.size()+" selected="+rows.size()
                    +" merged ranges="+EnergyReadingRepository.merge(captor.getValue()).size()+" plan ms="+mapper.readTree(plan).get(0).path("Execution Time"));
        }
    }

    @Test void comparisonRetainsCombinedBracketsAndQualityTotals() {
        Instant from=base.plusSeconds(10800),to=base.plusSeconds(28800),before=base.minusSeconds(3600);
        selects.set(0);
        var result=service.read(new DashboardEnergyReadRequest(request(from,to,before,base.plusSeconds(600)),true),scope());
        assertThat(selects.get()).isEqualTo(1);
        var broad=broad();
        Instant readFrom=before.minusSeconds(86400),readTo=base.plusSeconds(28800);
        assertLegacy(result.path("period"),from,to,readFrom,readTo,broad);
        assertLegacy(result.path("baseline"),before,base.plusSeconds(600),readFrom,readTo,broad);
    }

    @Test void overlappingAndAdjacentRangesPreserveDuplicateRowsAndExclusiveEndpoints() {
        var ranges=List.of(new Range(device,"source","energyWh",base.plusSeconds(2700),base.plusSeconds(2760),false),
                new Range(device,"source","energyWh",base.plusSeconds(2700),base.plusSeconds(2760),false),
                new Range(device,"source","energyWh",base.plusSeconds(2760),base.plusSeconds(2820),false));
        var rows=repository.readSelected(account,ranges).get(device);
        assertThat(EnergyReadingRepository.merge(ranges)).hasSize(1);
        //45 has two identical original rows;46 has three conflicting original rows.
        assertThat(rows).hasSize(5).allMatch(sample -> sample.reading().measuredAt().isBefore(base.plusSeconds(2820)));
    }

    @Test void selectedSampleLimitAndRangeParameterBudgetRemainBounded() {
        UUID limitDevice=UUID.randomUUID();
        jdbc.update("INSERT INTO telemetry.measurements SELECT ?,?,'activePowerW','W',n,'limit',?::timestamp+n*interval '1 second','OK' FROM generate_series(1,500001)n",
                limitDevice,account,Timestamp.from(base));
        var range=new Range(limitDevice,"limit","activePowerW",base,base.plusSeconds(600000),true);
        assertThatThrownBy(() -> repository.readSelected(account,List.of(range)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("500000");
        var tooMany=java.util.stream.IntStream.range(0,10001).mapToObj(index -> new Range(limitDevice,"limit","activePowerW",
                base.plusSeconds(index*2L),base.plusSeconds(index*2L+1),false)).toList();
        selects.set(0);
        assertThatThrownBy(() -> repository.readSelected(account,tooMany))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("10000");
        assertThat(selects.get()).isZero();
    }

    private void assertLegacy(JsonNode period,Instant from,Instant to,Instant readFrom,Instant readTo,List<StoredReading> broad) {
        var history=history();
        var calculator=new LegacyEnergyPeriodCalculator();
        double energy=0,covered=0;
        int position=0;
        for (var epoch:history) {
            if (!epoch.effectiveFrom().isBefore(to) || (epoch.effectiveTo()!=null && !epoch.effectiveTo().isAfter(from))) continue;
            int first=history.indexOf(epoch),last=first;
            while(first>0 && same(history.get(first-1),epoch) && history.get(first-1).effectiveTo().equals(history.get(first).effectiveFrom())) first--;
            while(last+1<history.size() && same(history.get(last+1),epoch) && history.get(last).effectiveTo()!=null
                    && history.get(last).effectiveTo().equals(history.get(last+1).effectiveFrom())) last++;
            Instant physicalFrom=history.get(first).effectiveFrom(),physicalTo=history.get(last).effectiveTo();
            String metric=epoch.authoritativeMethod().equals("COUNTER_DELTA")?"energyWh":"activePowerW";
            var samples=broad.stream().filter(sample -> sample.metric().equals(metric) && sample.reading().sourceId().equals(epoch.sourceId())
                    && !sample.reading().measuredAt().isBefore(readFrom) && !sample.reading().measuredAt().isAfter(readTo)
                    && !sample.reading().measuredAt().isBefore(physicalFrom) && (physicalTo==null || sample.reading().measuredAt().isBefore(physicalTo)))
                    .map(sample -> new LegacyEnergyPeriodCalculator.Reading(sample.reading().measuredAt(),sample.reading().value(),sample.reading().unit(),sample.reading().sourceId(),sample.reading().quality())).toList();
            var events=history.stream().filter(version -> same(version,epoch)).flatMap(version -> version.counterEvents().stream())
                    .map(event -> new LegacyEnergyPeriodCalculator.CounterEvent(event.at(),LegacyEnergyPeriodCalculator.EventKind.valueOf(event.kind()),event.rolloverWh())).distinct().toList();
            Instant start=epoch.effectiveFrom().isAfter(from)?epoch.effectiveFrom():from;
            Instant end=epoch.effectiveTo()!=null && epoch.effectiveTo().isBefore(to)?epoch.effectiveTo():to;
            var expected=calculator.calculate(samples,start,end,new LegacyEnergyPeriodCalculator.Policy(
                    LegacyEnergyPeriodCalculator.Method.valueOf(epoch.authoritativeMethod()),epoch.sourceId(),epoch.maxGapSeconds(),events));
            com.fasterxml.jackson.databind.node.ObjectNode evidence=mapper.valueToTree(expected);
            evidence.put("revision",epoch.revision()).put("physicalMeterId",epoch.physicalMeterId().toString()).put("sourceId",epoch.sourceId()).put("method",epoch.authoritativeMethod());
            assertThat(period.path("devices").get(0).path("epochs").get(position++)).isEqualTo(evidence);
            if(expected.energyKWh()!=null) energy+=expected.energyKWh();
            covered+=expected.coveredSeconds();
        }
        assertThat(period.path("coveredMeterSeconds").asDouble()).isEqualTo(covered);
        if(covered==0) assertThat(period.path("energyKWh").isNull()).isTrue();
        else assertThat(period.path("energyKWh").asDouble()).isEqualTo(energy);
    }

    private boolean same(EnergyMeterDefinition left,EnergyMeterDefinition right) {
        return left.physicalMeterId().equals(right.physicalMeterId()) && left.sourceId().equals(right.sourceId());
    }

    private long scannedRows(JsonNode node) {
        String type=node.path("Node Type").asText();
        long rows=List.of("Seq Scan","Index Scan","Index Only Scan","Bitmap Heap Scan").contains(type)
                ? (node.path("Actual Rows").asLong()+node.path("Rows Removed by Filter").asLong())*node.path("Actual Loops").asLong():0;
        for(var child:node) if(child.isContainerNode()) rows+=scannedRows(child);
        return rows;
    }
}
