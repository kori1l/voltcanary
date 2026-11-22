package com.voltcanary.device.repository;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.DeviceType;
import com.voltcanary.device.domain.Location;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Account-scoped filtering and offset pagination execute in SQL, including the device-type lookup. */
@Repository
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RegistryQueries {

    public record DeviceRow(Device device, DeviceType type) {}

    private final EntityManager entityManager;

    public List<DeviceRow> devices(
        UUID account,
        UUID location,
        String type,
        DeviceStatus status,
        String search,
        Integer limit,
        Integer offset
    ) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var device = query.from(Device.class);
        var deviceType = query.from(DeviceType.class);
        var predicates = new ArrayList<Predicate>();
        predicates.add(cb.equal(device.get("accountId"), account));
        predicates.add(cb.equal(device.get("deviceTypeId"), deviceType.get("id")));
        if (location != null) predicates.add(cb.equal(device.get("locationId"), location));
        if (status != null) predicates.add(cb.equal(device.get("status"), status));
        if (type != null && !type.isBlank()) predicates.add(
            cb.equal(cb.lower(deviceType.get("code")), type.toLowerCase(Locale.ROOT))
        );
        addSearch(cb, device, predicates, search, "name", "manufacturer", "model", "externalId");
        query
            .multiselect(device, deviceType)
            .where(predicates.toArray(Predicate[]::new))
            .orderBy(cb.asc(device.get("createdAt")), cb.asc(device.get("id")));
        return page(entityManager.createQuery(query), limit, offset)
            .getResultList()
            .stream()
            .map(row -> new DeviceRow(row.get(0, Device.class), row.get(1, DeviceType.class)))
            .toList();
    }

    public List<Location> locations(UUID account, String search, Integer limit, Integer offset) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Location.class);
        var location = query.from(Location.class);
        var predicates = new ArrayList<Predicate>();
        predicates.add(cb.equal(location.get("accountId"), account));
        addSearch(
            cb,
            location,
            predicates,
            search,
            "name",
            "description",
            "building",
            "floor",
            "room",
            "zone"
        );
        query
            .select(location)
            .where(predicates.toArray(Predicate[]::new))
            .orderBy(cb.asc(location.get("createdAt")), cb.asc(location.get("id")));
        return page(entityManager.createQuery(query), limit, offset).getResultList();
    }

    private static <T> TypedQuery<T> page(TypedQuery<T> query, Integer limit, Integer offset) {
        if (offset != null && offset < 0) throw new IllegalArgumentException(
            "offset must be greater than or equal to 0"
        );
        if (limit != null && (limit < 1 || limit > 200)) throw new IllegalArgumentException(
            "limit must be between 1 and 200"
        );
        query.setFirstResult(offset == null ? 0 : offset);
        if (limit != null) query.setMaxResults(limit);
        return query;
    }

    private static void addSearch(
        CriteriaBuilder cb,
        Root<?> root,
        List<Predicate> predicates,
        String search,
        String... fields
    ) {
        if (search == null || search.isBlank()) return;
        String pattern =
            "%" +
            search
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") +
            "%";
        var alternatives = new ArrayList<Predicate>();
        for (String field : fields)
            alternatives.add(cb.like(cb.lower(root.get(field)), pattern, '\\'));
        predicates.add(cb.or(alternatives.toArray(Predicate[]::new)));
    }
}
