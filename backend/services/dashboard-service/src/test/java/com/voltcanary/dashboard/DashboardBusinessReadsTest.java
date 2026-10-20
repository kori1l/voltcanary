package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.voltcanary.common.web.EnergyTariff;
import com.voltcanary.common.web.OperatingSchedule;
import com.voltcanary.common.web.OperationalFact;
import com.voltcanary.dashboard.adapter.DashboardBusinessReads;
import com.voltcanary.dashboard.domain.OperationalWindows;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardBusinessReadsTest {
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    final UUID account=UUID.randomUUID(),device=UUID.randomUUID(),source=UUID.randomUUID();
    final Instant from=Instant.parse("2026-10-01T00:00:00Z"),to=from.plusSeconds(3600);
    ReadRequest request(){return new ReadRequest(List.of(device),account,new Frame(from,to,to,"UTC",false,null,null,"NONE"),Set.of(),List.of(),10,0,"ACTIVE",List.of(),true,null);}
    EnergyTariff tariff(UUID id,String currency,String rate,Instant start,Instant end){return new EnergyTariff(id,1,account,null,currency,"UTC",new BigDecimal(rate),start,end,true,"Reviewed",false);}
    @Test void splitsBoundariesAndRoundsOnce(){
        var tariffs=mapper.createObjectNode().set("tariffs",mapper.valueToTree(List.of(tariff(UUID.randomUUID(),"EUR","0.005",from,from.plusSeconds(1800)),tariff(UUID.randomUUID(),"EUR","0.005",from.plusSeconds(1800),to))));
        var calls=new ArrayList<ReadRequest>();
        var result=new DashboardBusinessReads(mapper).cost(request(),tariffs,r->{calls.add(r);return mapper.createObjectNode().set("period",mapper.createObjectNode().put("energyKWh",1).put("coverage","COMPLETE"));});
        assertEquals(2,calls.size());assertEquals(from.plusSeconds(1800),calls.getFirst().time().to());
        assertEquals(new BigDecimal("0.01"),result.path("estimate").path("amount").decimalValue());
        assertFalse(result.path("estimate").path("billing").asBoolean());
    }
    @Test void missingTariffAndMixedCurrenciesDoNotInventAnAggregate(){
        var reads=new DashboardBusinessReads(mapper);
        var missing=reads.cost(request(),mapper.createObjectNode().set("tariffs",mapper.createArrayNode()),r->{throw new AssertionError("No tariff must not read energy");});
        assertTrue(missing.path("estimate").path("amount").isNull());
        var tariffs=mapper.createObjectNode().set("tariffs",mapper.valueToTree(List.of(tariff(UUID.randomUUID(),"EUR","1",from,from.plusSeconds(1800)),tariff(UUID.randomUUID(),"USD","1",from.plusSeconds(1800),to))));
        var mixed=reads.cost(request(),tariffs,r->mapper.createObjectNode().set("period",mapper.createObjectNode().put("energyKWh",1).put("coverage","COMPLETE")));
        assertEquals("MIXED_CURRENCY",mixed.path("estimate").path("coverage").asText());assertTrue(mixed.path("estimate").path("amount").isNull());
    }
    OperationalFact fact(String kind,Instant start,Instant end){return new OperationalFact(UUID.randomUUID(),1,device,source,UUID.randomUUID().toString(),kind,"COMPLETED",start,end,"Confirmed",null);}
    @Test void downtimeIsUnionedAndMissingObservationDoesNotInferAvailability(){
        var windows=new OperationalWindows();
        var unknown=windows.calculate(List.of(fact("DOWNTIME",from,to)),List.of(),from,to);assertNull(unknown.uptimeRatio());
        var result=windows.calculate(List.of(fact("OBSERVATION",from,to),fact("DOWNTIME",from,from.plusSeconds(1200)),fact("DOWNTIME",from.plusSeconds(600),from.plusSeconds(1800))),List.of(),from,to);
        assertEquals(1800,result.downtimeSeconds());assertEquals(0.5,result.uptimeRatio());
    }
    @Test void dstOverlapAndHolidayUseDeclaredLocalSchedule(){
        Instant start=Instant.parse("2026-10-25T00:00:00Z"),end=Instant.parse("2026-10-25T03:00:00Z");
        var schedule=new OperatingSchedule(UUID.randomUUID(),1,device,"Europe/Belgrade",start,end,List.of(new OperatingSchedule.Slot(7,LocalTime.of(2,0),LocalTime.of(3,0))),List.of(),"Confirmed");
        assertEquals(7200,new OperationalWindows().seconds(new OperationalWindows().scheduled(List.of(schedule),start,end)));
        var holiday=new OperatingSchedule(schedule.scheduleId(),2,device,schedule.timezone(),start,end,schedule.weekSlots(),List.of(LocalDate.of(2026,10,25)),"Holiday correction");
        assertTrue(new OperationalWindows().scheduled(List.of(schedule,holiday),start,end).isEmpty());
    }
    @Test void onlyContainedCompleteCyclesWithQualifiedEnergyEnterTheDenominator(){
        var completed=fact("CYCLE",from,from.plusSeconds(600));
        var gap=fact("CYCLE",from.plusSeconds(600),from.plusSeconds(1200));
        var crossing=fact("CYCLE",from.minusSeconds(60),from.plusSeconds(60));
        var incomplete=new OperationalFact(UUID.randomUUID(),1,device,source,"incomplete","CYCLE","INCOMPLETE",from,null,"Confirmed",null);
        var aborted=new OperationalFact(UUID.randomUUID(),1,device,source,"aborted","CYCLE","ABORTED",from,from.plusSeconds(100),"Confirmed",null);
        var input=mapper.createObjectNode();
        input.putArray("operations").addObject().put("deviceId",device.toString()).set("facts",mapper.valueToTree(List.of(completed,gap,crossing,incomplete,aborted)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("operations").get(0)).set("schedules",mapper.createArrayNode());
        var calls=new ArrayList<ReadRequest>();
        var output=new DashboardBusinessReads(mapper).operations(request(),input,r->{calls.add(r);return mapper.createObjectNode().set("period",mapper.createObjectNode().put("coverage",r.time().from().equals(from)?"COMPLETE":"PARTIAL").put("energyKWh",2));}).path("operations").get(0);
        assertEquals(2,calls.size());assertEquals(List.of(device),calls.getFirst().deviceIds());
        assertEquals(2,output.path("completedCycles").asInt());assertEquals(1,output.path("qualifiedCycles").asInt());
        assertEquals(2,output.path("incompleteCycles").asInt());assertEquals(1,output.path("abortedCycles").asInt());
        assertEquals(2,output.path("energyPerCycleKWh").asDouble());assertEquals("PARTIAL",output.path("cycleCoverage").asText());
        assertTrue(output.path("uptime").path("uptimeRatio").isNull());
    }
    @Test void absentFactsDoNotInventCyclesOrDowntimeAndDoNotReadPower(){
        var input=mapper.createObjectNode();
        var item=input.putArray("operations").addObject().put("deviceId",device.toString());
        item.set("facts",mapper.createArrayNode());item.set("schedules",mapper.createArrayNode());
        var output=new DashboardBusinessReads(mapper).operations(request(),input,r->{throw new AssertionError("Telemetry spikes cannot establish a cycle");}).path("operations").get(0);
        assertEquals(0,output.path("completedCycles").asInt());assertTrue(output.path("energyPerCycleKWh").isNull());
        assertEquals("UNKNOWN",output.path("uptime").path("coverage").asText());assertTrue(output.path("uptime").path("uptimeRatio").isNull());
    }
    @Test void springDstGapShiftsForwardWithoutInventingTheMissingHour(){
        Instant start=Instant.parse("2026-03-29T00:00:00Z"),end=Instant.parse("2026-03-29T04:00:00Z");
        var schedule=new OperatingSchedule(UUID.randomUUID(),1,device,"Europe/Belgrade",start,end,List.of(new OperatingSchedule.Slot(7,LocalTime.of(2,30),LocalTime.of(4,0))),List.of(),"Confirmed");
        assertEquals(1800,new OperationalWindows().seconds(new OperationalWindows().scheduled(List.of(schedule),start,end)));
    }
}
