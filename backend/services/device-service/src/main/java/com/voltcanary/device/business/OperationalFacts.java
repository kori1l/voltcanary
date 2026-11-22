package com.voltcanary.device.business;

import com.voltcanary.common.business.OperatingSchedule;
import com.voltcanary.common.business.OperationalFact;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OperationalFacts {

    private final OperationalRepository repository;
    private final BusinessResourceAuthorization access;
    private final DeviceAuditService audit;

    public List<OperationalFact> read(
        AuthenticatedUser actor,
        UUID account,
        UUID workspace,
        UUID device,
        Instant from,
        Instant to
    ) {
        if (
            from == null ||
            to == null ||
            !from.isBefore(to) ||
            Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
        ) throw invalid();
        access.device(actor, account, workspace, "operational.read", device);
        var facts = repository.facts(
            access.account(actor, account, workspace, "operational.read"),
            device,
            from,
            to
        );
        if (facts.size() > 500) throw invalid();
        return facts;
    }

    public List<OperatingSchedule> schedules(
        AuthenticatedUser actor,
        UUID account,
        UUID workspace,
        UUID device
    ) {
        access.device(actor, account, workspace, "operational.read", device);
        return repository.schedules(
            access.account(actor, account, workspace, "operational.read"),
            device
        );
    }

    @Transactional
    public List<OperationalFact> importFacts(
        AuthenticatedUser actor,
        UUID account,
        UUID workspace,
        List<OperationalFact> facts,
        boolean reviewed
    ) {
        if (facts == null || facts.isEmpty() || facts.size() > 100 || !reviewed) throw invalid();
        return facts
            .stream()
            .map(fact -> save(actor, account, workspace, fact, true))
            .toList();
    }

    @Transactional
    public OperationalFact save(
        AuthenticatedUser actor,
        UUID account,
        UUID workspace,
        OperationalFact request,
        boolean reviewed
    ) {
        if (
            request == null ||
            !reviewed ||
            request.deviceId() == null ||
            request.sourceId() == null ||
            request.sourceEventId() == null ||
            !request.sourceEventId().matches("[A-Za-z0-9_.:-]{1,160}") ||
            request.version() < 0 ||
            !List.of("CYCLE", "DOWNTIME", "PLANNED_DOWNTIME", "OBSERVATION").contains(
                request.kind()
            ) ||
            !List.of("COMPLETED", "ABORTED", "INCOMPLETE").contains(request.completeness()) ||
            request.startAt() == null ||
            request.startAt().isAfter(Instant.now()) ||
            (request.endAt() != null &&
                (!request.startAt().isBefore(request.endAt()) ||
                    request.endAt().isAfter(Instant.now()))) ||
            ("COMPLETED".equals(request.completeness()) && request.endAt() == null) ||
            request.reason() == null ||
            request.reason().isBlank() ||
            request.reason().length() > 500 ||
            (request.evidence() != null &&
                !request.evidence().equals("/devices/" + request.deviceId()))
        ) throw invalid();
        access.device(actor, account, workspace, "operational.write", request.deviceId());
        account = access.account(actor, account, workspace, "operational.write");
        repository.lock(account);
        if (!repository.sourceOwned(request.deviceId(), request.sourceId())) throw invalid();
        OperationalFact previous = repository.bySource(
            account,
            request.sourceId(),
            request.sourceEventId()
        );
        if (previous != null && request.version() == 0) {
            var retry = new OperationalFact(
                previous.eventId(),
                previous.version(),
                request.deviceId(),
                request.sourceId(),
                request.sourceEventId(),
                request.kind(),
                request.completeness(),
                request.startAt(),
                request.endAt(),
                request.reason(),
                request.evidence()
            );
            if (retry.equals(previous)) return previous;
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Source event already exists with different facts"
            );
        }
        if (
            request.version() != (previous == null ? 0 : previous.version()) ||
            (previous != null && !previous.deviceId().equals(request.deviceId())) ||
            (previous != null &&
                request.eventId() != null &&
                !previous.eventId().equals(request.eventId()))
        ) throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Operational fact revision changed"
        );
        OperationalFact saved = new OperationalFact(
            previous == null ? UUID.randomUUID() : previous.eventId(),
            request.version() + 1,
            request.deviceId(),
            request.sourceId(),
            request.sourceEventId(),
            request.kind(),
            request.completeness(),
            request.startAt(),
            request.endAt(),
            request.reason(),
            request.evidence()
        );
        repository.append(account, actor.id(), saved);
        audit.record(
            actor,
            account,
            previous == null ? "OPERATIONAL_FACT_CREATED" : "OPERATIONAL_FACT_CORRECTED",
            "operational",
            saved.eventId()
        );
        return saved;
    }

    @Transactional
    public OperatingSchedule schedule(
        AuthenticatedUser actor,
        UUID account,
        UUID workspace,
        OperatingSchedule request,
        boolean reviewed
    ) {
        if (
            request == null ||
            !reviewed ||
            request.deviceId() == null ||
            request.version() < 0 ||
            request.effectiveFrom() == null ||
            request.effectiveTo() == null ||
            !request.effectiveFrom().isBefore(request.effectiveTo()) ||
            request.weekSlots() == null ||
            request.weekSlots().size() > 28 ||
            request.holidays() == null ||
            request.holidays().size() > 366 ||
            request.holidays().stream().anyMatch(java.util.Objects::isNull) ||
            request.weekSlots().stream().anyMatch(java.util.Objects::isNull) ||
            request.reason() == null ||
            request.reason().isBlank() ||
            request.reason().length() > 500
        ) throw invalid();
        try {
            ZoneId.of(request.timezone());
        } catch (Exception bad) {
            throw invalid();
        }
        for (var slot : request.weekSlots()) {
            if (
                slot == null ||
                slot.dayOfWeek() < 1 ||
                slot.dayOfWeek() > 7 ||
                slot.from() == null ||
                slot.to() == null ||
                !slot.from().isBefore(slot.to())
            ) throw invalid();
            if (
                request
                    .weekSlots()
                    .stream()
                    .filter(
                        other ->
                            other.dayOfWeek() == slot.dayOfWeek() &&
                            slot.from().isBefore(other.to()) &&
                            other.from().isBefore(slot.to())
                    )
                    .count() != 1
            ) throw invalid();
        }
        access.device(actor, account, workspace, "operational.write", request.deviceId());
        account = access.account(actor, account, workspace, "operational.write");
        repository.lock(account);
        var history = repository.schedules(account, request.deviceId());
        if (history.size() >= 100) throw invalid();
        UUID id = request.scheduleId() == null ? UUID.randomUUID() : request.scheduleId();
        int latest = history
            .stream()
            .filter(s -> s.scheduleId().equals(id))
            .mapToInt(OperatingSchedule::version)
            .max()
            .orElse(0);
        if (latest != request.version()) throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Schedule revision changed"
        );
        if (
            history
                .stream()
                .anyMatch(
                    s ->
                        !s.scheduleId().equals(id) &&
                        request.effectiveFrom().isBefore(s.effectiveTo()) &&
                        s.effectiveFrom().isBefore(request.effectiveTo())
                )
        ) throw invalid();
        OperatingSchedule saved = new OperatingSchedule(
            id,
            latest + 1,
            request.deviceId(),
            request.timezone(),
            request.effectiveFrom(),
            request.effectiveTo(),
            List.copyOf(request.weekSlots()),
            List.copyOf(request.holidays()),
            request.reason()
        );
        repository.append(account, actor.id(), saved);
        audit.record(actor, account, "OPERATING_SCHEDULE_REVISION", "schedule", id);
        return saved;
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Review valid confirmed operational facts, source identity and schedule boundaries"
        );
    }
}
