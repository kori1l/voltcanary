package com.voltcanary.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardEnergyReadRequest;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import com.voltcanary.telemetry.repository.EnergyReadingRepository;
import com.voltcanary.telemetry.repository.EnergyReadingRepository.StoredReading;
import com.voltcanary.telemetry.service.EnergyPeriodReadService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EnergyPeriodReadServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EnergyReadingRepository repository = mock(EnergyReadingRepository.class);
    private final EnergyPeriodReadService service = new EnergyPeriodReadService(repository,mapper);
    private final Instant from = Instant.parse("2026-10-08T00:00:00Z");
    private final UUID account = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private EnergyMeterDefinition epoch(UUID physical,Instant start,Instant end,long revision) {
        return new EnergyMeterDefinition(revision,physical,null,"source","COUNTER_DELTA",7200,start,end,List.of());
    }
    private ObjectNode scope(Map<UUID,List<EnergyMeterDefinition>> history) {
        ObjectNode node = mapper.createObjectNode();
        var devices=node.putArray("devices");
        history.forEach((id,epochs)->{
            var device=devices.addObject().put("id",id.toString()).put("name","DEMO meter");
            device.set("energyMeters",mapper.valueToTree(epochs));
        });
        return node;
    }
    private DashboardEnergyReadRequest request(List<UUID> ids,boolean confirmed) {
        var frame=new DashboardOwnerReadRequest.TimeFrame(from,from.plusSeconds(7200),from.plusSeconds(14400),null,null);
        return new DashboardEnergyReadRequest(new DashboardOwnerReadRequest(user,account,ids,frame,Set.of(),List.of(),20,0,"ACTIVE",List.of()),confirmed);
    }
    private StoredReading reading(long seconds,double value,String metric,String unit) {
        return new StoredReading(metric,new Reading(from.plusSeconds(seconds),value,unit,"source","OK"));
    }

    @Test
    void replacementNeverBracketsReadingsAcrossDifferentPhysicalMeters() {
        UUID device=UUID.randomUUID();
        var history=Map.of(device,List.of(epoch(UUID.randomUUID(),from,from.plusSeconds(5400),1),
                epoch(UUID.randomUUID(),from.plusSeconds(5400),null,2)));
        when(repository.read(any(),anyList(),any(),any())).thenReturn(Map.of(device,List.of(
                reading(0,10000,"energyWh","Wh"),reading(3600,10400,"energyWh","Wh"),
                reading(5400,50000,"energyWh","Wh"),reading(7200,50100,"energyWh","Wh"))));
        var period=service.read(request(List.of(device),false),scope(history)).path("period");
        assertThat(period.path("energyKWh").asDouble()).isCloseTo(0.5,org.assertj.core.data.Offset.offset(0.000001));
        assertThat(period.path("coverage").asText()).isEqualTo("PARTIAL");
        assertThat(period.path("coverageFraction").asDouble()).isEqualTo(0.75);
    }

    @Test
    void policyRevisionOnTheSameMeterRetainsBracketingContinuity() {
        UUID device=UUID.randomUUID();
        UUID physical=UUID.randomUUID();
        var history=Map.of(device,List.of(epoch(physical,from,from.plusSeconds(5400),1),epoch(physical,from.plusSeconds(5400),null,2)));
        when(repository.read(any(),anyList(),any(),any())).thenReturn(Map.of(device,List.of(
                reading(0,10000,"energyWh","Wh"),reading(3600,10400,"energyWh","Wh"),reading(7200,10800,"energyWh","Wh"))));
        var period=service.read(request(List.of(device),false),scope(history)).path("period");
        assertThat(period.path("energyKWh").asDouble()).isEqualTo(0.8);
        assertThat(period.path("coverage").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void aSavedCounterMethodDoesNotAlsoAddPowerIntegration() {
        UUID device=UUID.randomUUID();
        var history=Map.of(device,List.of(epoch(UUID.randomUUID(),from,null,1)));
        when(repository.read(any(),anyList(),any(),any())).thenReturn(Map.of(device,List.of(
                reading(0,10000,"energyWh","Wh"),reading(7200,10500,"energyWh","Wh"),
                reading(0,1000,"activePowerW","W"),reading(7200,1000,"activePowerW","W"))));
        var period=service.read(request(List.of(device),false),scope(history)).path("period");
        assertThat(period.path("energyKWh").asDouble()).isEqualTo(0.5);
        assertThat(period.path("estimate").asBoolean()).isFalse();
        assertThat(period.path("peakRanking").get(0).path("maximumPowerW").asDouble()).isEqualTo(1000);
    }

    @Test
    void duplicatePhysicalAndAncestorSelectionsAreRejectedBeforeReading() {
        UUID left=UUID.randomUUID(),right=UUID.randomUUID(),physical=UUID.randomUUID();
        var duplicate=Map.of(left,List.of(epoch(physical,from,null,1)),right,List.of(epoch(physical,from,null,1)));
        assertThatThrownBy(()->service.read(request(List.of(left,right),true),scope(duplicate))).isInstanceOf(ResponseStatusException.class);
        var child=new EnergyMeterDefinition(1,UUID.randomUUID(),physical,"source","COUNTER_DELTA",7200,from,null,List.of(),List.of(physical),true);
        var related=Map.of(left,List.of(epoch(physical,from,null,1)),right,List.of(child));
        assertThatThrownBy(()->service.read(request(List.of(left,right),true),scope(related))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void multipleMetersNeedExplicitIndependenceAndMissingMeterIsPartialNotZero() {
        UUID known=UUID.randomUUID(),missing=UUID.randomUUID();
        var history=Map.of(known,List.of(epoch(UUID.randomUUID(),from,null,1)),missing,List.<EnergyMeterDefinition>of());
        assertThatThrownBy(()->service.read(request(List.of(known,missing),false),scope(history))).isInstanceOf(ResponseStatusException.class);
        when(repository.read(any(),anyList(),any(),any())).thenReturn(Map.of(known,List.of(
                reading(0,10000,"energyWh","Wh"),reading(7200,10500,"energyWh","Wh"))));
        var period=service.read(request(List.of(known,missing),true),scope(history)).path("period");
        assertThat(period.path("energyKWh").asDouble()).isEqualTo(0.5);
        assertThat(period.path("coverageFraction").asDouble()).isEqualTo(0.5);
        assertThat(period.path("devicesWithEvidence").asInt()).isEqualTo(1);
        assertThat(period.path("energyRanking")).hasSize(1);
        assertThat(period.path("devices").valueStream().filter(device->device.path("deviceId").asText().equals(missing.toString()))
                .findFirst().orElseThrow().path("energyKWh").isNull()).isTrue();
    }
}
