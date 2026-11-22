package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.configurable.domain.AutomationGrant;
import com.voltcanary.alert.configurable.domain.EvidencePolicy;
import com.voltcanary.alert.configurable.domain.EvidencePolicyDefinition;
import com.voltcanary.alert.configurable.domain.EvidencePolicyReview;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Uses the existing account/grant fence, including monotone revocation and fresh database clocks. */
@Component
@RequiredArgsConstructor
public class EvidencePolicySql {

    private final GrantSql grants;

    <T> T transaction(UUID account, GrantSql.Work<T> work) {
        return grants.transaction(account, connection -> {
            GrantSql.execute(
                connection,
                "UPDATE cfg_evidence_policy_review SET status='EXPIRED' WHERE account_id=? AND status IN ('RESERVED','PREPARED') AND expires_at<=?",
                account,
                OffsetDateTime.ofInstant(GrantSql.now(connection), ZoneOffset.UTC)
            );
            return work.run(connection);
        });
    }

    EvidencePolicyReview review(
        Connection connection,
        UUID account,
        UUID actor,
        UUID id
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM cfg_evidence_policy_review WHERE id=? AND account_id=? AND actor_id=? FOR UPDATE"
            )
        ) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var row = query.executeQuery()) {
                require(row.next(), "REVIEW_UNAVAILABLE");
                return read(row);
            }
        }
    }

    static EvidencePolicyReview read(ResultSet row) throws SQLException {
        return new EvidencePolicyReview(
            row.getObject("id", UUID.class),
            row.getObject("account_id", UUID.class),
            row.getObject("actor_id", UUID.class),
            row.getObject("grant_id", UUID.class),
            row.getObject("rule_id", UUID.class),
            row.getLong("grant_epoch"),
            row.getString("grant_definition_digest"),
            row.getObject("policy_id", UUID.class),
            row.getObject("receipt_id", UUID.class),
            row.getString("reservation_key"),
            row.getLong("revision"),
            row.getString("status"),
            row.getObject("expires_at", OffsetDateTime.class).toInstant(),
            row.getString("canonical_definition"),
            row.getString("review_digest"),
            row.getString("outcome_code")
        );
    }

    AutomationGrant grant(
        Connection connection,
        UUID account,
        UUID actor,
        UUID id
    ) throws SQLException {
        var grant = grants.grant(connection, actor, id);
        require(account.equals(grant.accountId()), "GRANT_UNAVAILABLE");
        require(
            grant.definitionDigest().equals(VclCanonical.sha256(grant.canonicalDefinition())),
            "GRANT_STATE_CORRUPT"
        );
        return grant;
    }

    AutomationGrant usable(Connection connection, EvidencePolicyReview review) throws SQLException {
        var grant = grant(connection, review.accountId(), review.actorId(), review.grantId());
        require(grant.status().equals("ACTIVE"), "AUTOMATION_GRANT_INACTIVE");
        require(
            grant.epoch() == review.grantEpoch() &&
                grant.definitionDigest().equals(review.grantDefinitionDigest()) &&
                grant.ruleId().equals(review.ruleId()),
            "GRANT_EPOCH_CONFLICT"
        );
        return grant;
    }

    EvidencePolicy policy(
        Connection connection,
        UUID account,
        UUID actor,
        UUID id
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM cfg_evidence_policy WHERE id=? AND account_id=? AND actor_id=?"
            )
        ) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var row = query.executeQuery()) {
                require(row.next(), "EVIDENCE_POLICY_UNAVAILABLE");
                var value = new EvidencePolicy(
                    id,
                    account,
                    actor,
                    row.getObject("rule_id", UUID.class),
                    row.getObject("grant_id", UUID.class),
                    row.getLong("grant_epoch"),
                    row.getString("grant_definition_digest"),
                    row.getObject("review_id", UUID.class),
                    row.getString("canonical_definition"),
                    row.getString("definition_digest"),
                    row.getObject("expires_at", OffsetDateTime.class).toInstant()
                );
                require(
                    value
                        .definitionDigest()
                        .equals(VclCanonical.sha256(value.canonicalDefinition())),
                    "EVIDENCE_POLICY_STATE_CORRUPT"
                );
                EvidencePolicyDefinition.parse(GrantJson.read(value.canonicalDefinition()));
                return value;
            }
        }
    }

    static void require(boolean value, String code) {
        if (!value) throw new VclException(code, "");
    }
}
