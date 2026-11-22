package com.voltcanary.device.service;

import com.voltcanary.device.domain.MaintenanceWindow;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class MaintenanceWindowFilters {

    private MaintenanceWindowFilters() {}

    static Specification<MaintenanceWindow> matching(
        UUID accountId,
        UUID deviceId,
        Instant from,
        Instant to,
        boolean includeCancelled
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (accountId != null) {
                predicates.add(criteriaBuilder.equal(root.get("accountId"), accountId));
            }
            predicates.add(criteriaBuilder.equal(root.get("deviceId"), deviceId));
            if (!includeCancelled) {
                predicates.add(criteriaBuilder.isNull(root.get("cancelledAt")));
            }
            if (from != null) {
                predicates.add(criteriaBuilder.greaterThan(root.get("endAt"), from));
            }
            if (to != null) {
                predicates.add(criteriaBuilder.lessThan(root.get("startAt"), to));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
