package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeAccessPolicy;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Actual actor, account/association locks and exact grant. Identity roles alone authorize no rows. */
final class NativeWorkspaceGrantScope {

    private NativeWorkspaceGrantScope() {}

    static NativeReadScope load(
        Connection connection,
        NativeSharedAccess access,
        NativeWorkspaceIdentity.Membership member
    ) throws SQLException {
        require(
            access.workspace().equals(member.workspace()) && access.actor().equals(member.actor()),
            "DATA_ACCESS_DENIED"
        );
        UUID actorAccount;
        try (var query = connection.prepareStatement("SELECT id FROM accounts WHERE user_id=?")) {
            query.setObject(1, access.actor());
            try (var row = query.executeQuery()) {
                require(row.next(), "DATA_ACCESS_DENIED");
                actorAccount = row.getObject(1, UUID.class);
            }
        }
        String ownerCut = null;
        String actorCut = null;
        UUID owner = null;
        // Deterministic order also covers two actors reading each other's associated accounts.
        try (
            var query = connection.prepareStatement(
                "SELECT id,user_id,status,updated_at FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE"
            )
        ) {
            query.setObject(1, access.account());
            query.setObject(2, actorAccount);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(rows.getString(3).equals("ACTIVE"), "DATA_ACCESS_DENIED");
                    UUID id = rows.getObject(1, UUID.class);
                    if (id.equals(access.account())) {
                        ownerCut = rows.getString(4);
                        owner = rows.getObject(2, UUID.class);
                    }
                    if (id.equals(actorAccount)) {
                        require(
                            rows.getObject(2, UUID.class).equals(access.actor()),
                            "DATA_ACCESS_DENIED"
                        );
                        actorCut = rows.getString(4);
                    }
                }
            }
        }
        require(ownerCut != null && actorCut != null, "DATA_ACCESS_DENIED");
        long association;
        try (
            var query = connection.prepareStatement(
                "SELECT version,consented_by FROM workspace_resource_accounts WHERE workspace_id=? AND account_id=? AND active=TRUE FOR UPDATE"
            )
        ) {
            query.setObject(1, access.workspace());
            query.setObject(2, access.account());
            try (var row = query.executeQuery()) {
                require(
                    row.next() && owner.equals(row.getObject(2, UUID.class)),
                    "DATA_ACCESS_DENIED"
                );
                association = row.getLong(1);
            }
        }
        try (
            var query = connection.prepareStatement(
                """
                SELECT g.subject_kind,g.subject_id,g.schema_revision,g.policy_digest,g.revision,
                    CASE WHEN OCTET_LENGTH(g.canonical_policy)<=65536 THEN g.canonical_policy ELSE NULL END,
                    g.review_id,g.created_by
                FROM cfg_native_access_grant g JOIN cfg_dataset d ON d.id=g.dataset_id AND d.account_id=g.account_id
                    AND d.schema_revision=g.schema_revision AND d.status='READY'
                WHERE g.id=? AND g.account_id=? AND g.workspace_id=? AND g.dataset_id=? AND g.status='ACTIVE'
                """
            )
        ) {
            query.setObject(1, access.grant());
            query.setObject(2, access.account());
            query.setObject(3, access.workspace());
            query.setObject(4, access.dataset());
            try (var row = query.executeQuery()) {
                require(row.next(), "DATA_ACCESS_DENIED");
                UUID subject = row.getObject(2, UUID.class);
                require(
                    (row.getString(1).equals("USER") && subject.equals(access.actor())) ||
                        (row.getString(1).equals("TEAM") && member.teams().contains(subject)),
                    "DATA_ACCESS_DENIED"
                );
                String canonical = row.getString(6);
                require(
                    canonical != null && VclCanonical.sha256(canonical).equals(row.getString(4)),
                    "NATIVE_GRANT_INTEGRITY"
                );
                var policy = NativeAccessPolicy.parse(NativeImportPlans.payload(canonical, false));
                require(
                    VclCanonical.encode(policy.json()).equals(canonical),
                    "NATIVE_GRANT_INTEGRITY"
                );
                var reviewed = JdbcNativeSharing.load(
                    connection,
                    access.account(),
                    row.getObject(8, UUID.class),
                    access.dataset(),
                    row.getObject(7, UUID.class)
                );
                JdbcNativeSharing.verify(reviewed);
                var definition = NativeImportPlans.payload(
                    reviewed.path("canonical_definition").asText(),
                    false
                );
                require(
                    reviewed.path("status").asText().equals("SUCCEEDED") &&
                        reviewed.path("grant_id").asText().equals(access.grant().toString()) &&
                        reviewed
                            .path("workspace_id")
                            .asText()
                            .equals(access.workspace().toString()) &&
                        reviewed.path("expected_schema_revision").longValue() == row.getLong(3) &&
                        definition.at("/subject/id").asText().equals(subject.toString()) &&
                        definition.at("/subject/kind").asText().equals(row.getString(1)) &&
                        VclCanonical.encode(definition.path("policy")).equals(canonical),
                    "NATIVE_GRANT_INTEGRITY"
                );
                var envelope = JsonNodeFactory.instance
                    .objectNode()
                    .put("profile", "native-workspace-access-1")
                    .put("accountId", access.account().toString())
                    .put("actorId", access.actor().toString())
                    .put("workspaceId", access.workspace().toString())
                    .put("datasetId", access.dataset().toString())
                    .put("grantId", access.grant().toString())
                    .put("grantRevision", row.getLong(5))
                    .put("schemaRevision", row.getLong(3))
                    .put("policyDigest", row.getString(4))
                    .put("membershipDigest", member.digest())
                    .put("associationVersion", association)
                    .put("ownerCut", ownerCut)
                    .put("actorCut", actorCut);
                return new NativeReadScope(
                    VclCanonical.sha256(VclCanonical.encode(envelope)),
                    access.dataset(),
                    policy,
                    access,
                    member,
                    association,
                    null
                );
            }
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
