package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.dto.AlertListFilters;
import com.voltcanary.alert.dto.AlertPageResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.dto.AlertSummaryResponse;
import com.voltcanary.alert.repository.AlertRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertReadQueries {
    private final AlertRepository alertRepository;
    private final jakarta.persistence.EntityManager entityManager;

    private Specification<Alert> specification(AlertListFilters filters) {
        return Specification.allOf(
                equalsIfPresent("deviceId", filters.deviceId()),
                equalsIfPresent("status", filters.status()),
                equalsIfPresent("severity", filters.severity()),
                equalsIfPresent("alertType", filters.alertType()),
                equalsIfPresent("ruleId", filters.ruleId()),
                createdAtFrom(filters.from()),
                createdAtTo(filters.to()));
    }
    private <T> Specification<Alert> equalsIfPresent(String field, T value) {
        return value == null ? Specification.unrestricted() : (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }
    private Specification<Alert> createdAtFrom(Instant from) {
        return from == null ? Specification.unrestricted() : (root, query, criteriaBuilder) -> criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), from);
    }
    private Specification<Alert> createdAtTo(Instant to) {
        return to == null ? Specification.unrestricted() : (root, query, criteriaBuilder) -> criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), to);
    }
    private Specification<Alert> scoped(UUID account, AlertListFilters filters) {
        return Specification.allOf(specification(filters), equalsIfPresent("accountId", account));
    }

    @Transactional(readOnly = true)
    public Alert snapshot(UUID id) {
        return alertRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> list(UUID account, AlertListFilters filters) {
        return alertRepository.findAll(scoped(account, filters), PageRequest.of(0, filters.normalizedLimit(),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).getContent().stream().map(AlertViews::response).toList();
    }

    @Transactional(readOnly = true)
    public AlertPageResponse page(UUID account, AlertListFilters filters, int page, int size) {
        var found = alertRepository.findAll(scoped(account, filters), PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new AlertPageResponse(found.getContent().stream().map(AlertViews::response).toList(), page, size,
                found.getTotalElements(), found.getTotalPages(), found.hasNext());
    }

    @Transactional(readOnly = true)
    public AlertSummaryResponse summary(UUID account, AlertListFilters filters) {
        var statuses = grouped(account, filters, "status");
        var severities = grouped(account, filters, "severity");
        long total = statuses.values().stream().mapToLong(Long::longValue).sum();
        for (var status : AlertStatus.values()) statuses.putIfAbsent(status.name(), 0L);
        for (var severity : AlertSeverity.values()) severities.putIfAbsent(severity.name(), 0L);
        return new AlertSummaryResponse(total, statuses, severities, grouped(account, filters, "deviceId"), Instant.now());
    }

    private Map<String, Long> grouped(UUID account, AlertListFilters filters, String field) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Object[].class);
        var root = query.from(Alert.class);
        query.multiselect(root.get(field), cb.count(root)).where(scoped(account, filters).toPredicate(root, query, cb)).groupBy(root.get(field));
        Map<String, Long> result = new LinkedHashMap<>();
        for (var row : entityManager.createQuery(query).getResultList()) result.put(row[0].toString(), ((Number) row[1]).longValue());
        return result;
    }

}
