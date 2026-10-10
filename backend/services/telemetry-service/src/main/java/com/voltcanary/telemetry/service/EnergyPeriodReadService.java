package com.voltcanary.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardEnergyReadRequest;
import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.CounterEvent;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.EventKind;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Method;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Policy;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import com.voltcanary.telemetry.repository.EnergyReadingRepository;
import com.voltcanary.telemetry.repository.EnergyReadingRepository.StoredReading;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Pure meter math composed with freshly authorized registry snapshots and raw telemetry. */
@Service
public class EnergyPeriodReadService {
    private record Meter(UUID deviceId,String name,UUID locationId,List<EnergyMeterDefinition> history) {
    }
    private final EnergyReadingRepository readings;
    private final ObjectMapper mapper;
    private final EnergyPeriodCalculator calculator = new EnergyPeriodCalculator();

    public EnergyPeriodReadService(EnergyReadingRepository readings,ObjectMapper mapper) {
        this.readings = readings;
        this.mapper = mapper;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ObjectNode read(DashboardEnergyReadRequest request,JsonNode renewedScope) {
        long deadline = System.nanoTime()+3_000_000_000L;
        var frame = request.scope().time();
        List<Meter> meters = prepare(request,renewedScope);
        Instant earliest = frame.compareFrom()!=null && frame.compareFrom().isBefore(frame.from())?frame.compareFrom():frame.from();
        Instant latest = frame.compareTo()!=null && frame.compareTo().isAfter(frame.to())?frame.compareTo():frame.to();
        Instant end = latest.plusSeconds(86400).isAfter(frame.asOf())?frame.asOf():latest.plusSeconds(86400);
        Map<UUID,List<StoredReading>> stored = readings.read(request.scope().accountId(),meters.stream().map(Meter::deviceId).toList(),earliest.minusSeconds(86400),end);
        ObjectNode result = mapper.createObjectNode().put("requestedCount",request.scope().deviceIds().size())
                .put("evaluatedCount",meters.size()).put("deniedBindingCount",request.scope().deviceIds().size()-meters.size())
                .put("asOf",frame.asOf().toString()).put("queryAt",Instant.now().toString())
                .put("intervalBasis","HALF_OPEN_UTC_RECORDED_AT")
                .put("weighting","ELAPSED_TIME_WITHIN_STABLE_CANONICAL_METER_EPOCHS")
                .put("maintenancePolicy","RAW_CONSUMPTION_INCLUDES_MAINTENANCE")
                .put("gapPolicy","REGISTRY_MAX_GAP_EXCLUDED_NOT_INTERPOLATED")
                .put("scopeWarning","Physical identity and independence are declared registry metadata; hidden overlap is not inferred")
                .put("rawSampleLimit",500000);
        result.set("authorizedDeviceIds",mapper.valueToTree(meters.stream().map(Meter::deviceId).toList()));
        result.set("period",period(meters,stored,frame.from(),frame.to(),deadline));
        if (frame.compareFrom()!=null) result.set("baseline",period(meters,stored,frame.compareFrom(),frame.compareTo(),deadline));
        else result.putNull("baseline");
        return result;
    }

    public ObjectNode validate(DashboardEnergyReadRequest request,JsonNode renewedScope) {
        List<Meter> meters = prepare(request,renewedScope);
        ObjectNode result=mapper.createObjectNode().put("evaluatedCount",meters.size())
                .put("deniedBindingCount",request.scope().deviceIds().size()-meters.size()).put("validationOnly",true);
        result.set("authorizedDeviceIds",mapper.valueToTree(meters.stream().map(Meter::deviceId).toList()));
        return result;
    }

    private List<Meter> prepare(DashboardEnergyReadRequest request,JsonNode renewedScope) {
        var frame=request.scope().time();
        if (frame.to().isAfter(frame.asOf()) || (frame.compareTo()!=null && frame.compareTo().isAfter(frame.asOf()))) {
            throw invalid("Energy periods cannot extend beyond the shared evidence watermark");
        }
        List<Meter> meters=new ArrayList<>();
        for (JsonNode device:renewedScope.path("devices")) {
            List<EnergyMeterDefinition> history=device.path("energyMeters").isArray()
                    ?mapper.convertValue(device.path("energyMeters"),new TypeReference<List<EnergyMeterDefinition>>() {}):List.of();
            UUID location=device.hasNonNull("locationId")?UUID.fromString(device.path("locationId").asText()):null;
            meters.add(new Meter(UUID.fromString(device.path("id").asText()),device.path("name").asText(),location,history));
        }
        if (meters.size()>1 && !request.independentMetersConfirmed()) throw invalid("Review independent physical meters before aggregating multiple devices");
        validateBoundary(meters,frame.from(),frame.to());
        if (frame.compareFrom()!=null) validateBoundary(meters,frame.compareFrom(),frame.compareTo());
        return meters;
    }

    private void validateBoundary(List<Meter> meters,Instant from,Instant to) {
        List<EnergyMeterDefinition> selected = meters.stream().flatMap(meter->meter.history().stream()).filter(epoch->overlaps(epoch,from,to)).toList();
        for (int index=0;index<selected.size();index++) {
            EnergyMeterDefinition left = selected.get(index);
            if (meters.size()>1 && !left.topologyComplete()) throw invalid("Meter ancestry is unresolved; select a reviewed non-overlapping boundary");
            for (int next=index+1;next<selected.size();next++) {
                EnergyMeterDefinition right = selected.get(next);
                Instant start = left.effectiveFrom().isAfter(right.effectiveFrom())?left.effectiveFrom():right.effectiveFrom();
                Instant end = left.effectiveTo()==null?to:left.effectiveTo();
                if (right.effectiveTo()!=null && right.effectiveTo().isBefore(end)) end=right.effectiveTo();
                if (!start.isBefore(end)) continue;
                if (left.physicalMeterId().equals(right.physicalMeterId())
                        || left.ancestorPhysicalMeterIds().contains(right.physicalMeterId())
                        || right.ancestorPhysicalMeterIds().contains(left.physicalMeterId())) {
                    throw invalid("Duplicate physical meter or parent/descendant overlap: choose parent or children, never both");
                }
            }
        }
    }

    private ObjectNode period(List<Meter> meters,Map<UUID,List<StoredReading>> stored,Instant from,Instant to,long deadline) {
        ObjectNode result = mapper.createObjectNode().put("from",from.toString()).put("to",to.toString()).put("unit","kWh")
                .put("coverageBasis","COVERED_METER_SECONDS_OVER_EXPECTED_METER_SECONDS");
        ArrayNode devices = result.putArray("devices");
        double total = 0;
        double covered = 0;
        int withEvidence = 0;
        boolean estimate = false;
        Map<String,List<ObjectNode>> locations = new LinkedHashMap<>();
        for (Meter meter:meters) {
            budget(deadline);
            List<StoredReading> samples = stored.getOrDefault(meter.deviceId(),List.of());
            ObjectNode device = device(meter,samples,from,to,deadline);
            devices.add(device);
            covered += device.path("coveredSeconds").asDouble();
            if (device.hasNonNull("energyKWh")) {
                withEvidence++;
                total += device.path("energyKWh").asDouble();
            }
            estimate |= device.path("estimate").asBoolean();
            locations.computeIfAbsent(meter.locationId()==null?"UNASSIGNED":meter.locationId().toString(),ignored->new ArrayList<>()).add(device);
        }
        if (!Double.isFinite(total)) throw invalid("Energy total exceeds numeric bounds");
        double expected = seconds(from,to)*meters.size();
        if (withEvidence==0) result.putNull("energyKWh"); else result.put("energyKWh",total);
        result.put("coverage",covered==0?"UNKNOWN":covered>=expected-0.000001?"COMPLETE":"PARTIAL")
                .put("coverageFraction",expected==0?0:Math.min(1,covered/expected)).put("coveredMeterSeconds",covered)
                .put("expectedMeterSeconds",expected).put("devicesWithEvidence",withEvidence).put("evaluatedDevices",meters.size())
                .put("estimate",estimate);
        ArrayNode ranking = result.putArray("energyRanking");
        devices.valueStream().filter(value->value.hasNonNull("energyKWh"))
                .sorted(Comparator.comparingDouble((JsonNode value)->value.path("energyKWh").asDouble()).reversed()
                        .thenComparing(value->value.path("deviceId").asText())).forEach(ranking::add);
        ArrayNode peakRanking = result.putArray("peakRanking");
        devices.valueStream().filter(value->value.hasNonNull("maximumPowerW"))
                .sorted(Comparator.comparingDouble((JsonNode value)->value.path("maximumPowerW").asDouble()).reversed()
                        .thenComparing(value->value.path("deviceId").asText())).forEach(peakRanking::add);
        ArrayNode locationTotals = result.putArray("locations");
        locations.forEach((location,entries)->{
            ObjectNode summary = locationTotals.addObject().put("locationId",location).put("evaluatedDevices",entries.size());
            long count = entries.stream().filter(value->value.hasNonNull("energyKWh")).count();
            if (count==0) summary.putNull("energyKWh");
            else summary.put("energyKWh",entries.stream().mapToDouble(value->value.path("energyKWh").asDouble()).sum());
            double seconds = entries.stream().mapToDouble(value->value.path("coveredSeconds").asDouble()).sum();
            double fraction = seconds/(seconds(from,to)*entries.size());
            summary.put("coverageFraction",Math.min(1,fraction)).put("coverage",fraction>=1-0.000001?"COMPLETE":fraction>0?"PARTIAL":"UNKNOWN");
        });
        return result;
    }

    private ObjectNode device(Meter meter,List<StoredReading> samples,Instant from,Instant to,long deadline) {
        ObjectNode result = mapper.createObjectNode().put("deviceId",meter.deviceId().toString()).put("deviceName",meter.name());
        if (meter.locationId()==null) result.putNull("locationId"); else result.put("locationId",meter.locationId().toString());
        double amount = 0;
        double covered = 0;
        boolean estimate = false;
        ArrayNode epochs = result.putArray("epochs");
        Double minPower = null;
        Double maxPower = null;
        Instant peakAt = null;
        for (EnergyMeterDefinition epoch:meter.history()) {
            budget(deadline);
            if (!overlaps(epoch,from,to)) continue;
            Instant start = epoch.effectiveFrom().isAfter(from)?epoch.effectiveFrom():from;
            Instant end = epoch.effectiveTo()!=null && epoch.effectiveTo().isBefore(to)?epoch.effectiveTo():to;
            Method method = Method.valueOf(epoch.authoritativeMethod());
            String metric = method==Method.COUNTER_DELTA?"energyWh":"activePowerW";
            List<Reading> source = physicalReadings(meter,epoch,samples,metric);
            // Supplementary historical reset declarations are carried by later metadata revisions.
            List<CounterEvent> events = meter.history().stream().filter(version->version.physicalMeterId().equals(epoch.physicalMeterId())
                    && version.sourceId().equals(epoch.sourceId())).flatMap(version->version.counterEvents().stream())
                    .map(event->new CounterEvent(event.at(),EventKind.valueOf(event.kind()),event.rolloverWh())).distinct().toList();
            var calculation = calculator.calculate(source,start,end,new Policy(method,epoch.sourceId(),epoch.maxGapSeconds(),events));
            ObjectNode evidence = mapper.valueToTree(calculation);
            evidence.put("revision",epoch.revision()).put("physicalMeterId",epoch.physicalMeterId().toString())
                    .put("sourceId",epoch.sourceId()).put("method",method.name());
            epochs.add(evidence);
            if (calculation.energyKWh()!=null) amount+=calculation.energyKWh();
            covered+=calculation.coveredSeconds();
            estimate |= calculation.estimate();
            var power = calculator.calculate(physicalReadings(meter,epoch,samples,"activePowerW"),start,end,
                    new Policy(Method.POWER_INTEGRAL,epoch.sourceId(),epoch.maxGapSeconds(),List.of()));
            if (power.minimumPowerW()!=null && (minPower==null || power.minimumPowerW()<minPower)) minPower=power.minimumPowerW();
            if (power.maximumPowerW()!=null && (maxPower==null || power.maximumPowerW()>maxPower)) {
                maxPower=power.maximumPowerW();
                peakAt=power.maximumPowerAt();
            }
        }
        if (covered==0) result.putNull("energyKWh"); else result.put("energyKWh",amount);
        double interval = seconds(from,to);
        result.put("coveredSeconds",covered).put("intervalSeconds",interval).put("coverageFraction",Math.min(1,covered/interval))
                .put("coverage",covered==0?"UNKNOWN":covered>=interval-0.000001?"COMPLETE":"PARTIAL").put("estimate",estimate);
        if (minPower==null) result.putNull("minimumPowerW"); else result.put("minimumPowerW",minPower);
        if (maxPower==null) result.putNull("maximumPowerW"); else result.put("maximumPowerW",maxPower);
        if (peakAt==null) result.putNull("maximumPowerAt"); else result.put("maximumPowerAt",peakAt.toString());
        return result;
    }

    private List<Reading> physicalReadings(Meter meter,EnergyMeterDefinition epoch,List<StoredReading> samples,String metric) {
        int first=meter.history().indexOf(epoch);
        int last=first;
        while (first>0 && sameIdentity(meter.history().get(first-1),epoch)
                && meter.history().get(first-1).effectiveTo()!=null
                && meter.history().get(first-1).effectiveTo().equals(meter.history().get(first).effectiveFrom())) first--;
        while (last+1<meter.history().size() && sameIdentity(meter.history().get(last+1),epoch)
                && meter.history().get(last).effectiveTo()!=null
                && meter.history().get(last).effectiveTo().equals(meter.history().get(last+1).effectiveFrom())) last++;
        Instant physicalFrom=meter.history().get(first).effectiveFrom();
        Instant physicalTo=meter.history().get(last).effectiveTo();
        return samples.stream().filter(sample->sample.metric().equals(metric) && epoch.sourceId().equals(sample.reading().sourceId())
                && !sample.reading().measuredAt().isBefore(physicalFrom)
                && (physicalTo==null || sample.reading().measuredAt().isBefore(physicalTo))).map(StoredReading::reading).toList();
    }

    private boolean sameIdentity(EnergyMeterDefinition left,EnergyMeterDefinition right) {
        return left.physicalMeterId().equals(right.physicalMeterId()) && left.sourceId().equals(right.sourceId());
    }

    private void budget(long deadline) {
        if (System.nanoTime()>=deadline) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Energy full-scope read exceeded its budget");
    }

    private boolean overlaps(EnergyMeterDefinition epoch,Instant from,Instant to) {
        return epoch.effectiveFrom().isBefore(to) && (epoch.effectiveTo()==null || epoch.effectiveTo().isAfter(from));
    }

    private double seconds(Instant from,Instant to) {
        return Duration.between(from,to).toNanos()/1_000_000_000d;
    }

    private ResponseStatusException invalid(String explanation) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,explanation);
    }
}
