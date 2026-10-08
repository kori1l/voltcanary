package com.voltcanary.device.service;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Applies owner and membership filters before paging the operation records. */
final class MaintenanceOperationFilters {
    private MaintenanceOperationFilters() {
    }

    static Specification<MaintenanceOperation> matching(
            UUID accountId, Instant from, Instant to, String status,
            boolean includeCancelled, List<UUID> deviceIds, UUID locationId) {
        Instant now = Instant.now();
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("accountId"), accountId));
            if (!includeCancelled) predicates.add(builder.isNull(root.get("cancelledAt")));
            if (from != null) predicates.add(builder.greaterThan(root.get("endAt"), from));
            if (to != null) predicates.add(builder.lessThan(root.get("startAt"), to));
            if (status != null) {
                if ("CANCELLED".equals(status)) {
                    predicates.add(builder.isNotNull(root.get("cancelledAt")));
                } else {
                    predicates.add(builder.isNull(root.get("cancelledAt")));
                    switch (status) {
                        case "SCHEDULED" -> predicates.add(builder.greaterThan(root.get("startAt"), now));
                        case "ACTIVE" -> {
                            predicates.add(builder.lessThanOrEqualTo(root.get("startAt"), now));
                            predicates.add(builder.greaterThan(root.get("endAt"), now));
                        }
                        case "FINISHED" -> predicates.add(builder.lessThanOrEqualTo(root.get("endAt"), now));
                        default -> throw new IllegalArgumentException("Unsupported maintenance status");
                    }
                }
            }
            if (!deviceIds.isEmpty() || locationId != null) {
                var membership = query.subquery(UUID.class);
                var window = membership.from(MaintenanceWindow.class);
                List<Predicate> members = new ArrayList<>();
                members.add(builder.equal(window.get("operationId"), root.get("id")));
                members.add(builder.equal(window.get("accountId"), accountId));
                if (!deviceIds.isEmpty()) members.add(window.get("deviceId").in(deviceIds));
                if (locationId != null) {
                    var device = membership.from(Device.class);
                    members.add(builder.equal(device.get("id"), window.get("deviceId")));
                    members.add(builder.equal(device.get("accountId"), accountId));
                    members.add(builder.equal(device.get("locationId"), locationId));
                }
                membership.select(window.get("operationId")).where(members.toArray(Predicate[]::new));
                predicates.add(builder.exists(membership));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
