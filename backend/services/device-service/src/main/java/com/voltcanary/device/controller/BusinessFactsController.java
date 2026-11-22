package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.business.EnergyTariff;
import com.voltcanary.common.business.OperatingSchedule;
import com.voltcanary.common.business.OperationalFact;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.device.business.BusinessResourceAuthorization;
import com.voltcanary.device.business.EnergyTariffPolicy;
import com.voltcanary.device.business.OperationalFacts;
import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class BusinessFactsController {

    public record TariffCommand(EnergyTariff tariff, boolean reviewed) {}

    public record FactCommand(
        UUID accountId,
        UUID workspaceId,
        OperationalFact fact,
        boolean reviewed
    ) {}

    public record ImportCommand(
        UUID accountId,
        UUID workspaceId,
        List<OperationalFact> facts,
        boolean reviewed
    ) {}

    public record ScheduleCommand(
        UUID accountId,
        UUID workspaceId,
        OperatingSchedule schedule,
        boolean reviewed
    ) {}

    private final EnergyTariffPolicy tariffs;
    private final OperationalFacts operations;
    private final BusinessResourceAuthorization access;
    private final AuthenticatedUserResolver users;
    private final InternalServiceAuth internal;
    private final ObjectMapper mapper;

    @GetMapping("/energy-tariffs")
    public List<EnergyTariff> tariffs(
        @RequestHeader("Authorization") String authorization,
        @RequestParam(value = "accountId", required = false) UUID accountId,
        @RequestParam(value = "workspaceId", required = false) UUID workspaceId
    ) {
        return tariffs.list(actor(authorization), accountId, workspaceId);
    }

    @PostMapping({ "/energy-tariffs", "/energy-tariffs/revisions" })
    public EnergyTariff save(
        @RequestHeader("Authorization") String authorization,
        @RequestBody TariffCommand command
    ) {
        return tariffs.save(actor(authorization), command.tariff(), command.reviewed());
    }

    @PostMapping("/energy-tariffs/{id}/archive")
    public void archive(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id,
        @RequestParam(value = "accountId", required = false) UUID accountId,
        @RequestParam(value = "workspaceId", required = false) UUID workspaceId
    ) {
        tariffs.archive(actor(authorization), accountId, workspaceId, id);
    }

    @GetMapping("/operational-events")
    public List<OperationalFact> facts(
        @RequestHeader("Authorization") String authorization,
        @RequestParam(value = "deviceId") UUID deviceId,
        @RequestParam(value = "from") Instant from,
        @RequestParam(value = "to") Instant to,
        @RequestParam(value = "accountId", required = false) UUID accountId,
        @RequestParam(value = "workspaceId", required = false) UUID workspaceId
    ) {
        return operations.read(actor(authorization), accountId, workspaceId, deviceId, from, to);
    }

    @PostMapping("/operational-events")
    public OperationalFact fact(
        @RequestHeader("Authorization") String authorization,
        @RequestBody FactCommand command
    ) {
        return operations.save(
            actor(authorization),
            command.accountId(),
            command.workspaceId(),
            command.fact(),
            command.reviewed()
        );
    }

    @PostMapping("/operational-events/import")
    public List<OperationalFact> importFacts(
        @RequestHeader("Authorization") String authorization,
        @RequestBody ImportCommand command
    ) {
        return operations.importFacts(
            actor(authorization),
            command.accountId(),
            command.workspaceId(),
            command.facts(),
            command.reviewed()
        );
    }

    @GetMapping("/operating-schedules")
    public List<OperatingSchedule> schedules(
        @RequestHeader("Authorization") String authorization,
        @RequestParam(value = "deviceId") UUID deviceId,
        @RequestParam(value = "accountId", required = false) UUID accountId,
        @RequestParam(value = "workspaceId", required = false) UUID workspaceId
    ) {
        return operations.schedules(actor(authorization), accountId, workspaceId, deviceId);
    }

    @PostMapping("/operating-schedules")
    public OperatingSchedule schedule(
        @RequestHeader("Authorization") String authorization,
        @RequestBody ScheduleCommand command
    ) {
        return operations.schedule(
            actor(authorization),
            command.accountId(),
            command.workspaceId(),
            command.schedule(),
            command.reviewed()
        );
    }

    @PostMapping("/internal/dashboard/tariffs")
    public ObjectNode tariffRead(
        @RequestHeader("X-Internal-Service-Token") String token,
        @RequestHeader("Authorization") String authorization,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        AuthenticatedUser actor = internalActor(token, authorization, request);
        if (
            access
                .subset(
                    actor,
                    request.accountId(),
                    request.workspaceId(),
                    "tariff.read",
                    Set.copyOf(request.deviceIds())
                )
                .size() != request.deviceIds().size()
        ) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tariff scope changed");
        var result = envelope(request);
        result.set(
            "tariffs",
            mapper.valueToTree(tariffs.list(actor, request.accountId(), request.workspaceId()))
        );
        return result;
    }

    @PostMapping("/internal/dashboard/operations")
    public ObjectNode operationRead(
        @RequestHeader("X-Internal-Service-Token") String token,
        @RequestHeader("Authorization") String authorization,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        AuthenticatedUser actor = internalActor(token, authorization, request);
        var allowed = access.subset(
            actor,
            request.accountId(),
            request.workspaceId(),
            "operational.read",
            Set.copyOf(request.deviceIds())
        );
        if (allowed.size() != request.deviceIds().size()) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Operational scope changed"
        );
        var result = envelope(request);
        var devices = result.putArray("operations");
        int count = 0;
        for (UUID device : request.deviceIds()) {
            var facts = operations.read(
                actor,
                request.accountId(),
                request.workspaceId(),
                device,
                request.time().from(),
                request.time().to()
            );
            count += facts.size();
            if (count > 500) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Choose a narrower operational scope"
            );
            var item = devices.addObject().put("deviceId", device.toString());
            item.set("facts", mapper.valueToTree(facts));
            item.set(
                "schedules",
                mapper.valueToTree(
                    operations.schedules(actor, request.accountId(), request.workspaceId(), device)
                )
            );
        }
        result
            .put("factPolicy", "CONFIRMED_EXPLICIT_EVENTS_ONLY")
            .put("maintenancePolicy", "PLANNED_AND_OBSERVED_DOWNTIME_SEPARATE")
            .put("dstPolicy", "GAP_SHIFT_FORWARD_OVERLAP_EARLIER_OFFSET_HALF_OPEN");
        return result;
    }

    private ObjectNode envelope(DashboardOwnerReadRequest request) {
        var result = mapper
            .createObjectNode()
            .put("evaluatedCount", request.deviceIds().size())
            .put("deniedBindingCount", 0)
            .put("queryAt", Instant.now().toString());
        result.set("authorizedDeviceIds", mapper.valueToTree(request.deviceIds()));
        return result;
    }

    private AuthenticatedUser internalActor(
        String token,
        String authorization,
        DashboardOwnerReadRequest request
    ) {
        internal.require(token);
        AuthenticatedUser actor = actor(authorization);
        if (
            request == null ||
            !actor.id().equals(request.userId()) ||
            request.accountId() == null ||
            request.deviceIds().size() > 50 ||
            request.time() == null ||
            request.time().from() == null ||
            request.time().to() == null ||
            !request.time().from().isBefore(request.time().to()) ||
            java.time.Duration.between(request.time().from(), request.time().to()).compareTo(
                java.time.Duration.ofDays(31)
            ) > 0
        ) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid business evidence read"
            );
        }
        return actor;
    }

    private AuthenticatedUser actor(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return users.resolve(headers);
    }
}
