package com.voltcanary.dashboard.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.EnergyTariff;
import com.voltcanary.common.web.OperatingSchedule;
import com.voltcanary.common.web.OperationalFact;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.OperationalWindows;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DashboardBusinessReads {
    private final ObjectMapper mapper;
    private final OperationalWindows windows=new OperationalWindows();

    public ObjectNode batched(Function<Function<ReadRequest,JsonNode>,ObjectNode> calculation,
            Function<List<ReadRequest>,List<JsonNode>> batch) {
        var intervals = new java.util.LinkedHashSet<ReadRequest>();
        calculation.apply(request -> {
            intervals.add(request);
            return mapper.createObjectNode().set("period",mapper.createObjectNode());
        });
        var requests = List.copyOf(intervals);
        if (requests.isEmpty()) return calculation.apply(request -> { throw new IllegalStateException("Unexpected interval"); });
        var results = batch.apply(requests);
        if (results.size()!=requests.size()) throw new DashboardFailure("INVALID_READ_RESPONSE","Energy batch interval count differs");
        var memo = new java.util.HashMap<ReadRequest,JsonNode>();
        for (int i=0;i<requests.size();i++) {
            JsonNode result = results.get(i);
            if (result.path("deniedBindingCount").asLong()>0) throw new DashboardFailure("NO_ACCESS","Energy interval scope changed");
            var ids = new java.util.HashSet<String>();
            result.path("authorizedDeviceIds").forEach(id -> ids.add(id.asText()));
            var expected = requests.get(i).deviceIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
            if (!ids.equals(expected) || result.path("evaluatedCount").asInt(-1)!=expected.size())
                throw new DashboardFailure("INVALID_READ_RESPONSE","Energy interval scope differs");
            memo.put(requests.get(i),result);
        }
        return calculation.apply(memo::get);
    }
    public ReadRequest interval(ReadRequest request,Instant from,Instant to) {
        return new ReadRequest(request.deviceIds(),request.accountId(),new Frame(from,to,request.time().asOf(),request.time().timezone(),true,null,null,"NONE"),request.metrics(),request.detailDeviceIds(),request.rows(),request.offset(),request.incidentMode(),request.series(),request.independentMetersConfirmed(),request.workspaceId());
    }
    public ObjectNode cost(ReadRequest request,JsonNode tariffSource,Function<ReadRequest,JsonNode> energy) {
        ObjectNode result=costPeriod(request,tariffSource,energy);
        if (request.time().compareFrom()!=null && request.time().compareTo()!=null) {
            var baselineRequest=interval(request,request.time().compareFrom(),request.time().compareTo());
            var baseline=costPeriod(baselineRequest,tariffSource,energy);
            result.set("baseline",baseline.path("estimate"));
            var current=result.path("estimate"); var previous=baseline.path("estimate");
            boolean comparable=java.time.Duration.between(request.time().from(),request.time().to()).equals(java.time.Duration.between(baselineRequest.time().from(),baselineRequest.time().to()))
                && "COMPLETE".equals(current.path("coverage").asText()) && "COMPLETE".equals(previous.path("coverage").asText())
                && current.path("currency").equals(previous.path("currency"));
            result.set("comparison",mapper.valueToTree(com.voltcanary.dashboard.query.QueryTypes.Comparison.of(comparable?current.path("amount").asDouble():null,comparable?previous.path("amount").asDouble():null)));
        }
        return result;
    }
    private ObjectNode costPeriod(ReadRequest request,JsonNode tariffSource,Function<ReadRequest,JsonNode> energy) {
        List<EnergyTariff> tariffs=mapper.convertValue(tariffSource.path("tariffs"),mapper.getTypeFactory().constructCollectionType(List.class,EnergyTariff.class));
        TreeSet<Instant> boundaries=new TreeSet<>(List.of(request.time().from(),request.time().to()));
        for (var tariff:tariffs) {
            if(tariff.archivedAt()!=null && tariff.archivedAt().isAfter(request.time().from()) && tariff.archivedAt().isBefore(request.time().to())) boundaries.add(tariff.archivedAt());
            if (tariff.effectiveFrom().isAfter(request.time().from()) && tariff.effectiveFrom().isBefore(request.time().to())) boundaries.add(tariff.effectiveFrom());
            if (tariff.effectiveTo().isAfter(request.time().from()) && tariff.effectiveTo().isBefore(request.time().to())) boundaries.add(tariff.effectiveTo());
        }
        if (boundaries.size()>33) throw new DashboardFailure("LIMIT_EXCEEDED","At most32 tariff intervals are supported per read");
        var result=envelope(request);
        var estimate=result.putObject("estimate").put("label","Estimate").put("fixedFeesExcluded",true).put("exchangeExcluded",true).put("billing",false);
        var segments=estimate.putArray("segments");
        String currency=null;
        BigDecimal total=BigDecimal.ZERO;
        boolean partial=false,known=false,mixed=false;
        List<Instant> edges=List.copyOf(boundaries);
        for (int i=1;i<edges.size();i++) {
            Instant from=edges.get(i-1),to=edges.get(i);
            var active=tariffs.stream().filter(t->(!t.archived() || t.archivedAt()!=null && !t.archivedAt().isBefore(to)) && !t.effectiveFrom().isAfter(from) && !t.effectiveTo().isBefore(to))
                    .sorted(java.util.Comparator.comparingInt(EnergyTariff::version).reversed()).toList();
            if (active.isEmpty()) {
                partial=true;
                segments.addObject().put("from",from.toString()).put("to",to.toString()).put("state","MISSING_TARIFF");
                continue;
            }
            EnergyTariff tariff=active.getFirst();
            if (active.stream().anyMatch(t->!t.tariffId().equals(tariff.tariffId()))) throw new DashboardFailure("INVALID_READ_RESPONSE","Tariff intervals overlap");
            JsonNode period=energy.apply(interval(request,from,to)).path("period");
            var segment=segments.addObject().put("from",from.toString()).put("to",to.toString()).put("tariffId",tariff.tariffId().toString())
                    .put("tariffVersion",tariff.version()).put("currency",tariff.currency()).put("ratePerKWh",tariff.ratePerKWh()).put("taxesIncluded",tariff.taxesIncluded());
            segment.set("energyProvenance",period);
            if (!period.path("energyKWh").isNumber()) {
                partial=true;
                segment.put("state","UNKNOWN_ENERGY");
                continue;
            }
            BigDecimal kwh=period.path("energyKWh").decimalValue();
            if (kwh.signum()<0) throw new DashboardFailure("INVALID_READ_RESPONSE","Negative qualified energy");
            BigDecimal amount=kwh.multiply(tariff.ratePerKWh());
            if (currency!=null && !currency.equals(tariff.currency())) mixed=true;
            currency=tariff.currency();
            total=total.add(amount);
            known=true;
            partial|=!"COMPLETE".equals(period.path("coverage").asText());
            segment.put("amount",amount).put("state",period.path("coverage").asText());
        }
        estimate.put("coverage",mixed?"MIXED_CURRENCY":!known?"UNKNOWN":partial?"PARTIAL":"COMPLETE");
        if (mixed || !known) estimate.putNull("amount").putNull("currency");
        else {
            estimate.put("currency",currency).put("amount",total.setScale(Math.max(0,java.util.Currency.getInstance(currency).getDefaultFractionDigits()),RoundingMode.HALF_EVEN));
        }
        estimate.put("roundingPolicy","SUM_EXACT_DECIMALS_THEN_CURRENCY_HALF_EVEN");
        return result;
    }
    public ObjectNode operations(ReadRequest request,JsonNode source,Function<ReadRequest,JsonNode> energy) {
        var result=envelope(request);
        var output=result.putArray("operations");
        int energyReads=0;
        for (JsonNode device:source.path("operations")) {
            List<OperationalFact> facts=mapper.convertValue(device.path("facts"),mapper.getTypeFactory().constructCollectionType(List.class,OperationalFact.class));
            List<OperatingSchedule> schedules=mapper.convertValue(device.path("schedules"),mapper.getTypeFactory().constructCollectionType(List.class,OperatingSchedule.class));
            var item=output.addObject().put("deviceId",device.path("deviceId").asText());
            item.set("uptime",mapper.valueToTree(windows.calculate(facts,schedules,request.time().from(),request.time().to())));
            int completed=0,aborted=0,incomplete=0,qualified=0;
            BigDecimal consumption=BigDecimal.ZERO;
            var cycles=item.putArray("cycles");
            for (var fact:facts) if ("CYCLE".equals(fact.kind())) {
                if ("ABORTED".equals(fact.completeness())) {aborted++;continue;}
                if (!"COMPLETED".equals(fact.completeness()) || fact.endAt()==null || fact.startAt().isBefore(request.time().from()) || fact.endAt().isAfter(request.time().to())) {incomplete++;continue;}
                completed++;
                if (++energyReads>32) throw new DashboardFailure("LIMIT_EXCEEDED","Choose at most32 confirmed cycles per read");
                var scoped=interval(request,fact.startAt(),fact.endAt());
                var one=new ReadRequest(List.of(fact.deviceId()),scoped.accountId(),scoped.time(),scoped.metrics(),List.of(),scoped.rows(),0,"ACTIVE",List.of(),true,scoped.workspaceId());
                var period=energy.apply(one).path("period");
                var cycle=cycles.addObject().put("eventId",fact.eventId().toString()).put("version",fact.version());
                cycle.set("energy",period);
                if ("COMPLETE".equals(period.path("coverage").asText()) && period.path("energyKWh").isNumber()) {
                    qualified++;
                    consumption=consumption.add(period.path("energyKWh").decimalValue());
                }
            }
            item.put("completedCycles",completed).put("abortedCycles",aborted).put("incompleteCycles",incomplete).put("qualifiedCycles",qualified);
            if (qualified>0) item.put("energyPerCycleKWh",consumption.divide(BigDecimal.valueOf(qualified),8,RoundingMode.HALF_EVEN));
            else item.putNull("energyPerCycleKWh");
            item.put("cycleCoverage",qualified==0?"UNKNOWN":qualified<completed?"PARTIAL":"COMPLETE");
        }
        result.put("factPolicy","CONFIRMED_EVENTS_NO_SPIKE_OR_GAP_INFERENCE");
        return result;
    }
    private ObjectNode envelope(ReadRequest request) {
        var node=mapper.createObjectNode().put("evaluatedCount",request.deviceIds().size()).put("deniedBindingCount",0).put("queryAt",Instant.now().toString());
        node.set("authorizedDeviceIds",mapper.valueToTree(request.deviceIds()));
        return node;
    }
}
