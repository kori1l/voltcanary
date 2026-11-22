package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reference-only evidence with a retention lease independent of the effect receipt. */
@Component
@RequiredArgsConstructor
public class RuleAlertEvidence {

    private final JdbcTemplate jdbc;

    static void append(
        Connection connection,
        UUID account,
        UUID device,
        UUID proposalId,
        WindowAlertProposal proposal,
        int retentionDays,
        Instant admittedAt
    ) throws SQLException {
        if (retentionDays < 1 || retentionDays > 30) throw new VclException(
            "EVIDENCE_POLICY_CHANGED",
            ""
        );
        try (
            var insert = connection.prepareStatement(
                """
                INSERT INTO cfg_rule_alert_private_evidence(account_id,proposal_id,policy_id,binding_id,schema_revision,
                    snapshot_id,input_generation,coverage_proof_id,coverage_digest,measure_id,measure_digest,expires_at,device_id,
                    observation_references)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """
            )
        ) {
            insert.setObject(1, account);
            insert.setObject(2, proposalId);
            insert.setObject(3, proposal.uuid("policy"));
            insert.setObject(4, proposal.binding());
            insert.setLong(5, proposal.schemaRevision());
            insert.setObject(6, proposal.uuid("snapshot"));
            insert.setObject(7, proposal.uuid("generation"));
            insert.setObject(8, proposal.uuid("coverageProof"));
            insert.setString(9, proposal.text("coverageDigest"));
            insert.setObject(10, proposal.uuid("measure"));
            insert.setString(11, proposal.text("measureDigest"));
            insert.setObject(
                12,
                admittedAt.plus(retentionDays, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC)
            );
            insert.setObject(13, device);
            insert.setString(
                14,
                com.voltcanary.vcl.VclCanonical.encode(proposal.observationReferences())
            );
            insert.executeUpdate();
        }
    }

    @Scheduled(fixedDelayString = "${app.alerts.rule-evidence-cleanup-interval:5m}")
    @Transactional
    public void purgeExpired() {
        jdbc.execute(
            (ConnectionCallback<Integer>) connection -> {
                var now = OwnerDatabaseClock.now(connection).atOffset(ZoneOffset.UTC);
                try (
                    var delete = connection.prepareStatement(
                        """
                        DELETE FROM cfg_rule_alert_private_evidence WHERE (account_id,proposal_id) IN (
                            SELECT account_id,proposal_id FROM cfg_rule_alert_private_evidence
                            WHERE expires_at<=? ORDER BY expires_at,account_id,proposal_id FETCH FIRST 256 ROWS ONLY)
                        """
                    )
                ) {
                    delete.setObject(1, now);
                    return delete.executeUpdate();
                }
            }
        );
    }
}
