package com.voltcanary.device.configurable.application;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Joins account deletion's transaction and row fence; retained evidence cannot become active again. */
@Service
@RequiredArgsConstructor
public class NativeAccountResourceArchive {
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entities;

    public void archive(UUID accountId) {
        // Flush the managed DELETED status first: native jobs lock/check this same account row.
        entities.flush();
        jdbc.update("UPDATE cfg_native_history_lease SET released_at=CURRENT_TIMESTAMP WHERE account_id=? AND released_at IS NULL AND expires_at>CURRENT_TIMESTAMP", accountId);
        jdbc.update("UPDATE cfg_native_access_grant SET status='REVOKED',revision=revision+1 WHERE account_id=? AND status='ACTIVE'", accountId);
        jdbc.update("UPDATE cfg_native_access_review SET status='CANCELLED' WHERE account_id=? AND status='PREPARED'", accountId);
        jdbc.update("UPDATE cfg_native_schema_migration SET status='CANCELLED',outcome_code='ACCOUNT_ARCHIVED' WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')", accountId);
        jdbc.update("DELETE FROM cfg_import_artifact WHERE account_id=?", accountId);
        jdbc.update("UPDATE cfg_import_job SET status='CANCELLED' WHERE account_id=? AND status='PREPARED'", accountId);
        jdbc.update("UPDATE cfg_import_progress SET status='CANCELLED',revision=revision+1,outcome_code='ACCOUNT_ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE account_id=? AND status IN ('RUNNING','PARTIAL','FAILED')", accountId);
        jdbc.update("UPDATE cfg_native_generation SET status='ARCHIVED' WHERE account_id=?", accountId);
        jdbc.update("DELETE FROM cfg_native_query_snapshot WHERE account_id=?", accountId);
        jdbc.update("UPDATE cfg_dataset SET status='ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE account_id=? AND status<>'ARCHIVED'", accountId);
        jdbc.update("""
                UPDATE cfg_collection_review SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP
                WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')
                """, accountId);
        jdbc.update("UPDATE cfg_row_review SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')", accountId);
    }
}
