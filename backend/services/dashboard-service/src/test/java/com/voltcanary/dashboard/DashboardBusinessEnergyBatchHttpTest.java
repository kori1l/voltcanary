package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.web.OperationalFact;
import com.voltcanary.dashboard.adapter.HttpDashboardEvidenceReads;
import com.voltcanary.dashboard.config.DashboardReadProperties;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DashboardBusinessEnergyBatchHttpTest {
    @Test void cyclesOneSixTwelveTwentyFourThirtyTwoUseOneEnergyHttpBatch() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        UUID device=UUID.randomUUID(),source=UUID.randomUUID();
        var actor=new DashboardActor(UUID.randomUUID(),UUID.randomUUID());
        var from=Instant.parse("2026-10-11T00:00:00Z");var to=from.plusSeconds(3600);
        var count=new AtomicInteger();var batches=new AtomicInteger();var factsCalls=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/internal/dashboard/operations",exchange->{
            factsCalls.incrementAndGet();
            var result=mapper.createObjectNode().put("evaluatedCount",1).put("deniedBindingCount",0);result.putArray("authorizedDeviceIds").add(device.toString());
            var facts=java.util.stream.IntStream.range(0,count.get()).mapToObj(i->new OperationalFact(UUID.randomUUID(),1,device,source,"cycle-"+i,"CYCLE","COMPLETED",from.plusSeconds(i*100),from.plusSeconds((i+1)*100),"Confirmed",null)).toList();
            var item=result.putArray("operations").addObject().put("deviceId",device.toString());
            item.set("facts",mapper.valueToTree(facts));item.putArray("schedules");
            byte[] body=mapper.writeValueAsBytes(result);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/internal/dashboard/energy-batch",exchange->{
            batches.incrementAndGet();
            var request=mapper.readTree(exchange.getRequestBody());
            assertThat(request.path("intervals")).hasSize(count.get());
            var result=mapper.createObjectNode().put("asOf",request.path("scope").path("time").path("asOf").asText());
            var intervals=result.putObject("intervals");
            request.path("intervals").forEach(interval->{
                var value=intervals.putObject(interval.path("key").asText()).put("evaluatedCount",1).put("deniedBindingCount",0);
                value.set("authorizedDeviceIds",interval.path("deviceIds"));value.putObject("period").put("energyKWh",2).put("coverage","COMPLETE");
            });
            byte[] body=mapper.writeValueAsBytes(result);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var uri=URI.create("http://localhost:"+server.getAddress().getPort());
            var adapter=new HttpDashboardEvidenceReads(new DashboardReadProperties(uri,uri,uri,uri,"test-service",Duration.ofSeconds(3)),mapper);
            var request=new ReadRequest(List.of(device),actor.accountId(),new Frame(from,to,to,"UTC",false,null,null,"NONE"),Set.of(),List.of(),1,0,"ACTIVE",List.of(),true);
            for(int cycles:List.of(1,6,12,24,32)) {
                count.set(cycles);batches.set(0);factsCalls.set(0);
                var result=adapter.operations("Bearer test",actor,request).path("operations").get(0);
                assertThat(result.path("qualifiedCycles").asInt()).isEqualTo(cycles);
                assertThat(result.path("energyPerCycleKWh").asDouble()).isEqualTo(2);
                assertThat(batches.get()).isEqualTo(1);assertThat(factsCalls.get()).isEqualTo(1);
            }
        } finally {server.stop(0);}
    }
}
