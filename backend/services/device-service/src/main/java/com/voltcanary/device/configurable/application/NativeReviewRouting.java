package com.voltcanary.device.configurable.application;

import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Review kind is resolved from owned durable records, never a client-selected execution route. */
@Service
@RequiredArgsConstructor
public class NativeReviewRouting {

    private final JdbcTemplate jdbc;
    private final NativeRowWorkflows accounts;

    public boolean row(AuthenticatedUser actor, UUID id) {
        UUID account = accounts.account(actor);
        var kinds = jdbc.queryForList(
            """
            SELECT 'COLLECTION' FROM cfg_collection_review WHERE account_id=? AND actor_id=? AND id=?
            UNION ALL SELECT 'ROW' FROM cfg_row_review WHERE account_id=? AND actor_id=? AND id=?
            """,
            String.class,
            account,
            actor.id(),
            id,
            account,
            actor.id(),
            id
        );
        if (kinds.isEmpty()) throw new VclException("REVIEW_UNAVAILABLE", "");
        if (kinds.size() != 1) throw new VclException("REVIEW_INTEGRITY", "");
        return kinds.getFirst().equals("ROW");
    }
}
