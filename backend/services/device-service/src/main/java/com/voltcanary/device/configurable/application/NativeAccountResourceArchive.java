package com.voltcanary.device.configurable.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Joins account deletion's transaction and row fence; retained evidence cannot become active again. */
@Service
public class NativeAccountResourceArchive {
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entities;

    public NativeAccountResourceArchive(JdbcTemplate jdbc, jakarta.persistence.EntityManager entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    public void archive(UUID accountId) {
        // Flush the managed DELETED status first: native jobs lock/check this same account row.
        entities.flush();
        jdbc.update("DELETE FROM cfg_native_query_snapshot WHERE account_id=?", accountId);
        jdbc.update("UPDATE cfg_dataset SET status='ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE account_id=? AND status<>'ARCHIVED'", accountId);
        jdbc.update("""
                UPDATE cfg_collection_review SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP
                WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')
                """, accountId);
        jdbc.update("UPDATE cfg_row_review SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')", accountId);
    }
}
