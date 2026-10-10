package com.voltcanary.device.service;

import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.dto.EnergyMeterRequest;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.EnergyMeterRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Owner-managed canonical meter revisions; raw measurements and maintenance policies are untouched. */
@Service
public class EnergyMeterRegistry {
    private final AccountService accounts;
    private final DeviceRepository devices;
    private final EnergyMeterRepository meters;
    private final DeviceAuditService audit;

    public EnergyMeterRegistry(AccountService accounts,DeviceRepository devices,EnergyMeterRepository meters,DeviceAuditService audit) {
        this.accounts = accounts;
        this.devices = devices;
        this.meters = meters;
        this.audit = audit;
    }

    @Transactional(readOnly=true)
    public List<EnergyMeterDefinition> read(AuthenticatedUser actor,UUID deviceId) {
        Account account = ownedAccount(actor,deviceId);
        return meters.definitions(account.getId(),List.of(deviceId)).getOrDefault(deviceId,List.of());
    }

    @Transactional
    public EnergyMeterDefinition save(AuthenticatedUser actor,UUID deviceId,EnergyMeterRequest request) {
        Account account = ownedAccount(actor,deviceId);
        validate(request);
        if (!meters.lockAccount(account.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account access changed before saving");
        UUID source;
        try { source=UUID.fromString(request.sourceId()); }
        catch (IllegalArgumentException invalid) { throw invalid("Select a canonical registered equipment source"); }
        if (!meters.sourceOwned(account.getId(),deviceId,source)) throw invalid("Select a source registered to this equipment");
        List<EnergyMeterDefinition> history = meters.definitions(account.getId(),List.of(deviceId)).getOrDefault(deviceId,List.of());
        EnergyMeterDefinition latest = history.isEmpty()?null:history.getLast();
        long revision = latest==null?0:latest.revision();
        if (request.expectedRevision()!=revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Meter metadata changed; reload before saving");
        }
        if (history.size()>=32 || (latest!=null && !request.effectiveFrom().isAfter(latest.effectiveFrom()))) {
            throw invalid("At most32 ordered meter epochs are supported; replacement starts after the previous epoch");
        }
        validateTopology(request,meters.current(account.getId()),latest);
        EnergyMeterDefinition saved = meters.append(account.getId(),deviceId,request,revision+1);
        audit.record(actor,account.getId(),"ENERGY_METER_REVISION","device",deviceId);
        return saved;
    }

    private Account ownedAccount(AuthenticatedUser actor,UUID deviceId) {
        Account account = accounts.findByUserId(actor.id()).filter(value->value.getStatus()==AccountStatus.ACTIVE)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Active account required"));
        if (devices.findByIdAndAccountId(deviceId,account.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Device not found");
        }
        return account;
    }

    private void validate(EnergyMeterRequest request) {
        if (request==null || request.expectedRevision()<0 || request.physicalMeterId()==null
                || request.physicalMeterId().equals(request.parentPhysicalMeterId())
                || request.sourceId()==null || !request.sourceId().matches("[A-Za-z0-9_.:-]{1,160}")
                || request.authoritativeMethod()==null || !List.of("COUNTER_DELTA","POWER_INTEGRAL").contains(request.authoritativeMethod())
                || request.maxGapSeconds()<1 || request.maxGapSeconds()>86400
                || request.effectiveFrom()==null || request.effectiveFrom().isBefore(Instant.EPOCH)
                || request.effectiveFrom().isAfter(Instant.now())
                || (request.counterEvents()!=null && request.counterEvents().size()>32)) {
            throw invalid("Canonical meter/source/method, effective UTC time and bounded gap policy are required");
        }
        Set<Instant> times = new HashSet<>();
        if (request.counterEvents()!=null) for (var event:request.counterEvents()) {
            if (event==null || event.at()==null || event.at().isBefore(Instant.EPOCH) || event.at().isAfter(Instant.now())
                    || !times.add(event.at()) || event.kind()==null || !List.of("RESET","ROLLOVER","REPLACEMENT").contains(event.kind())
                    || ("ROLLOVER".equals(event.kind()) && (event.rolloverWh()==null
                    || !Double.isFinite(event.rolloverWh()) || event.rolloverWh()<=0))
                    || (!"ROLLOVER".equals(event.kind()) && event.rolloverWh()!=null)) {
                throw invalid("Counter events require unique recorded times and an explicit rollover modulus");
            }
        }
    }

    private void validateTopology(EnergyMeterRequest request,List<EnergyMeterDefinition> current,EnergyMeterDefinition latest) {
        Map<UUID,UUID> parents = new HashMap<>();
        for (EnergyMeterDefinition meter:current) {
            if (parents.containsKey(meter.physicalMeterId()) && !java.util.Objects.equals(parents.get(meter.physicalMeterId()),meter.parentPhysicalMeterId())) {
                throw invalid("Physical meter topology is ambiguous; resolve conflicting metadata first");
            }
            parents.put(meter.physicalMeterId(),meter.parentPhysicalMeterId());
        }
        if (request.parentPhysicalMeterId()!=null && !parents.containsKey(request.parentPhysicalMeterId())) {
            throw invalid("The parent physical meter must be registered in this account");
        }
        long identities=current.stream().filter(meter->meter.physicalMeterId().equals(request.physicalMeterId())).count();
        boolean sameCurrent=latest!=null && latest.physicalMeterId().equals(request.physicalMeterId());
        if (identities>(sameCurrent?1:0) && !java.util.Objects.equals(parents.get(request.physicalMeterId()),request.parentPhysicalMeterId())) {
            throw invalid("One physical meter cannot have conflicting upstream relationships");
        }
        parents.put(request.physicalMeterId(),request.parentPhysicalMeterId());
        for (UUID start:parents.keySet()) {
            Set<UUID> visited = new HashSet<>();
            UUID currentId = start;
            while (currentId!=null) {
                if (!visited.add(currentId) || visited.size()>32) throw invalid("Meter topology must be acyclic with at most32 levels");
                currentId = parents.get(currentId);
            }
        }
    }

    private ResponseStatusException invalid(String explanation) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,explanation);
    }
}
