package com.voltcanary.device.service;

import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceQueries {
    private final EntityManager entityManager;

    public List<MaintenanceOperation> operations(Specification<MaintenanceOperation> spec, int offset, int limit) {
        return page(MaintenanceOperation.class, spec, offset, limit, true);
    }

    public List<MaintenanceWindow> windows(Specification<MaintenanceWindow> spec, String status, int offset, int limit) {
        Instant now = Instant.now();
        Specification<MaintenanceWindow> state = (root, query, cb) -> {
            if (status == null) return cb.conjunction();
            if ("CANCELLED".equals(status)) return cb.isNotNull(root.get("cancelledAt"));
            var active = cb.isNull(root.get("cancelledAt"));
            return cb.and(active, switch (status) {
                case "SCHEDULED" -> cb.greaterThan(root.get("startAt"), now);
                case "ACTIVE" -> cb.and(cb.lessThanOrEqualTo(root.get("startAt"), now), cb.greaterThan(root.get("endAt"), now));
                case "FINISHED" -> cb.lessThanOrEqualTo(root.get("endAt"), now);
                default -> throw new IllegalArgumentException("Unsupported maintenance status");
            });
        };
        return page(MaintenanceWindow.class, spec.and(state), offset, limit, false);
    }

    private <T> List<T> page(Class<T> type, Specification<T> spec, int offset, int limit, boolean ascending) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(type);
        var root = query.from(type);
        query.select(root).where(spec.toPredicate(root, query, cb))
                .orderBy(ascending ? cb.asc(root.get("startAt")) : cb.desc(root.get("startAt")), cb.asc(root.get("id")));
        return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(limit).getResultList();
    }
}
