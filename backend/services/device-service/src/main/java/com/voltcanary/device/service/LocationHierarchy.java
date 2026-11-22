package com.voltcanary.device.service;

import com.voltcanary.device.exception.NotFoundException;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes hierarchy writes per account and reads fresh ancestor pointers. */
@Service
@RequiredArgsConstructor
public class LocationHierarchy {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void validate(UUID account, UUID location, UUID proposedParent) {
        jdbc.query("select id from accounts where id=? for update", (rs, index) -> rs.getObject(1, UUID.class), account);
        var visited = new HashSet<UUID>();
        UUID cursor = proposedParent;
        while (cursor != null) {
            if (cursor.equals(location) || !visited.add(cursor)) throw new IllegalArgumentException("Location hierarchy cannot contain a cycle");
            var parents = jdbc.query("select parent_id from locations where id=? and account_id=?",
                    (rs, index) -> rs.getObject(1, UUID.class), cursor, account);
            if (parents.isEmpty()) throw new NotFoundException("Parent location not found");
            cursor = parents.getFirst();
        }
    }
}
