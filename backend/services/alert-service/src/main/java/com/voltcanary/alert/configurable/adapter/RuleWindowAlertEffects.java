package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.configurable.application.AutomationGrantReviews;
import com.voltcanary.alert.configurable.application.EvidencePolicyReviews;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.service.AlertMaintenanceSuppressionService;
import com.voltcanary.alert.service.DeviceHistoryDeletion;
import com.voltcanary.alert.notification.application.NotificationPreferenceService;
import com.voltcanary.alert.notification.port.AlertNotificationPort;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert-owner admission with grant fencing and a durable receipt for each exact proposal. */
@Service
public class RuleWindowAlertEffects {
    public record Receipt(UUID proposalId, String proposalDigest,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            UUID alertId, UUID receiptId) { }

    private final JdbcTemplate jdbc;
    private final AutomationGrantReviews grants;
    private final EvidencePolicyReviews policies;
    private final WorkerGrantScopeAuthority sources;
    private final AlertMaintenanceSuppressionService suppression;
    private final DeviceHistoryDeletion deviceHistory;
    private final NotificationPreferenceService notificationPreferences;
    private final AlertNotificationPort notifications;

    public RuleWindowAlertEffects(JdbcTemplate jdbc, AutomationGrantReviews grants,
            EvidencePolicyReviews policies, WorkerGrantScopeAuthority sources, AlertMaintenanceSuppressionService suppression,
            DeviceHistoryDeletion deviceHistory, NotificationPreferenceService notificationPreferences,
            AlertNotificationPort notifications) {
        this.jdbc = jdbc;
        this.grants = grants;
        this.policies = policies;
        this.sources = sources;
        this.suppression = suppression;
        this.deviceHistory = deviceHistory;
        this.notificationPreferences = notificationPreferences;
        this.notifications = notifications;
    }

    @Transactional
    public Receipt apply(VclWorkerAssertion.Scope worker, UUID proposalId, byte[] bytes) {
        var proposal = scopedProposal(worker, proposalId, bytes);
        var grant = grants.machineGrant(retarget(worker, worker.grant()), proposal.grantEpoch());
        var scope = AutomationGrantScope.parse(grant.definition());
        require(scope.sourceBindings().stream().anyMatch(binding -> binding.id().equals(proposal.binding())
                && binding.schemaRevision() == proposal.schemaRevision()), "ALERT_PROPOSAL_SCOPE_CHANGED");
        String grantDigest = VclCanonical.sha256("AUTOMATION_GRANT/1:" + grant.id() + ":" + grant.accountId() + ":"
                + grant.actorId() + ":" + grant.ruleId() + ":" + grant.epoch() + ":" + grant.status() + ":" + grant.definitionDigest());
        require(grantDigest.equals(proposal.text("grantDigest")), "GRANT_CHANGED");
        var policy = policies.machinePolicy(retarget(worker, proposal.uuid("policy")), proposal.uuid("policy"));
        String policyDigest = policySnapshotDigest(worker, policy.id(), policy.grantEpoch(), policy.grantDefinitionDigest(),
                policy.expiresAt(), policy.definitionDigest());
        require(policyDigest.equals(proposal.text("policyDigest")), "EVIDENCE_POLICY_CHANGED");
        return jdbc.execute((ConnectionCallback<Receipt>) connection -> {
            lockAuthority(connection, worker, grant, policy);
            var existing = receipt(connection, worker.account(), proposalId);
            if (existing != null) {
                require(existing.proposalDigest().equals(proposal.digest()), "ALERT_PROPOSAL_CONFLICT");
                return existing;
            }
            UUID device = sources.verifyWindowSource(worker, proposal);
            require(deviceHistory.writable(device), "OWNER_ACCESS_REVOKED");
            var now = OwnerDatabaseClock.now(connection);
            require(now.isBefore(grant.expiresAt()) && now.isBefore(policy.expiresAt()), "AUTOMATION_GRANT_INACTIVE");
            String dedup = "vcl:" + worker.account() + ":" + proposal.text("dedup");
            var occurrence = existingOccurrence(connection, worker.account(), dedup);
            if (proposal.retraction()) {
                if (occurrence != null && occurrence.alert() != null) {
                    retractAlert(connection, worker, device, occurrence.alert(), proposalId, proposal, now);
                }
            } else if (occurrence == null) {
                UUID alert = null;
                if (!suppression.suppressesEvent(device, proposalId, "CONFIGURABLE_RULE_MATCHED",
                        proposal.start(), proposal.effectEnd(), proposal.effectEnd())) {
                    alert = insertAlert(connection, worker, device, proposal, dedup, now);
                    UUID event = appendCreatedEvent(connection, alert, device, worker.rule(), proposal, now);
                    if (notificationPreferences.configurableRuleDeliveryEnabled(worker.account())) {
                        notifications.alertCreated(new AlertNotificationRequest(alert, event, worker.account(), device,
                                null, null, null, worker.rule().toString(), proposal.start(), proposal.end(),
                                proposal.text("measureDigest"), "CONFIGURABLE_RULE_MATCHED", "CONFIGURABLE_RULE",
                                proposal.severity().name(), "OPEN", "Rule condition matched", "A rule condition matched.",
                                now, event + ":CONFIGURABLE_RULE_MATCHED"));
                    }
                }
                occurrence = new Occurrence(alert);
                recordOccurrence(connection, worker.account(), dedup, occurrence);
            } else if (proposal.correction() && occurrence.alert() != null
                    && !suppression.suppressesEvent(device, proposalId, "CONFIGURABLE_RULE_MATCHED",
                            proposal.start(), proposal.end(), proposal.end())) {
                reopenAlert(connection, worker, device, occurrence.alert(), proposalId, proposal, now);
            }
            var result = new Receipt(proposalId, proposal.digest(), occurrence == null ? null : occurrence.alert(), UUID.randomUUID());
            try (var insert = connection.prepareStatement("""
                    INSERT INTO cfg_rule_alert_effect_receipt(account_id,proposal_id,proposal_digest,original_alert_id,receipt_id)
                    VALUES (?,?,?,?,?)
                    """)) {
                insert.setObject(1, worker.account());
                insert.setObject(2, proposalId);
                insert.setString(3, proposal.digest());
                insert.setObject(4, result.alertId());
                insert.setObject(5, result.receiptId());
                insert.executeUpdate();
            }
            RuleAlertEvidence.append(connection, worker.account(), device, proposalId, proposal,
                    policy.definition().path("retentionDays").intValue(), now);
            var committedAt = OwnerDatabaseClock.now(connection);
            require(committedAt.isBefore(grant.expiresAt()) && committedAt.isBefore(policy.expiresAt()),
                    "AUTOMATION_GRANT_INACTIVE");
            return result;
        });
    }

    private static void retractAlert(Connection connection, VclWorkerAssertion.Scope worker, UUID device, UUID alert,
            UUID proposalId, WindowAlertProposal proposal, java.time.Instant now) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE alerts SET status='RESOLVED',resolved_at=?
                WHERE id=? AND account_id=? AND device_id=? AND rule_id=? AND status IN ('OPEN','ACKNOWLEDGED')
                """)) {
            update.setTimestamp(1, Timestamp.from(now));
            update.setObject(2, alert);
            update.setObject(3, worker.account());
            update.setObject(4, device);
            update.setString(5, worker.rule().toString());
            if (update.executeUpdate() == 0) return;
        }
        appendCorrectionEvent(connection, worker, device, alert, proposalId, proposal, now,
                "CONFIGURABLE_RULE_RETRACTED", "RESOLVED",
                proposal.aggregate() ? "AGGREGATE_THRESHOLD_NO_LONGER_MET"
                        : proposal.count() ? "COUNT_THRESHOLD_NO_LONGER_MET" : proposal.interval() ? "INTERVAL_THRESHOLD_NO_LONGER_MET"
                        : proposal.sustained() ? "SUSTAINED_EPISODE_REMOVED" : "SESSION_WITNESS_CONFIRMED");
        try (var update = connection.prepareStatement("""
                UPDATE notification_attempts SET status='DISABLED',provider_response_summary=?,updated_at=?,
                    locked_at=NULL,locked_by=NULL,retry_exhausted=FALSE
                WHERE account_id=? AND alert_id=? AND delivery_event_type='CONFIGURABLE_RULE_MATCHED'
                    AND status IN ('PENDING','RETRYING')
                """)) {
            update.setString(1, "Configurable rule alert was retracted");
            update.setTimestamp(2, Timestamp.from(now));
            update.setObject(3, worker.account());
            update.setObject(4, alert);
            update.executeUpdate();
        }
    }

    private static void reopenAlert(Connection connection, VclWorkerAssertion.Scope worker, UUID device, UUID alert,
            UUID proposalId, WindowAlertProposal proposal, java.time.Instant now) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE alerts SET status='OPEN',resolved_at=NULL
                WHERE id=? AND account_id=? AND device_id=? AND rule_id=? AND status='RESOLVED'
                  AND EXISTS (SELECT 1 FROM alert_events e WHERE e.alert_id=alerts.id
                    AND e.event_type='CONFIGURABLE_RULE_RETRACTED' AND e.created_at=alerts.resolved_at)
                """)) {
            update.setObject(1, alert);
            update.setObject(2, worker.account());
            update.setObject(3, device);
            update.setString(4, worker.rule().toString());
            if (update.executeUpdate() == 0) return;
        }
        appendCorrectionEvent(connection, worker, device, alert, proposalId, proposal, now,
                "CONFIGURABLE_RULE_REOPENED", "OPEN",
                proposal.aggregate() ? "AGGREGATE_THRESHOLD_CONFIRMED"
                        : proposal.count() ? "COUNT_THRESHOLD_CONFIRMED" : proposal.interval() ? "INTERVAL_THRESHOLD_CONFIRMED"
                        : proposal.sustained() ? "SUSTAINED_EPISODE_CONFIRMED" : "SESSION_ABSENCE_CONFIRMED");
    }

    private static void appendCorrectionEvent(Connection connection, VclWorkerAssertion.Scope worker, UUID device,
            UUID alert, UUID proposalId, WindowAlertProposal proposal, java.time.Instant now,
            String type, String status, String reason) throws SQLException {
        UUID eventId = UUID.randomUUID();
        var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("eventId", eventId.toString()).put("alertId", alert.toString()).putNull("actorId")
                .put("action", type).put("timestamp", now.toString()).put("status", status).put("reason", reason);
        payload.putObject("context").put("deviceId", device.toString()).put("ruleId", worker.rule().toString())
                .put("calculationWindowStart", proposal.start().toString()).put("calculationWindowEnd", proposal.end().toString())
                .putObject("details").put("proposalId", proposalId.toString()).put("measureDigest", proposal.text("measureDigest"));
        try (var insert = connection.prepareStatement("""
                INSERT INTO alert_events(id,alert_id,event_type,payload_json,created_at)
                SELECT ?,id,?,?,? FROM alerts WHERE id=? AND account_id=? AND device_id=? AND rule_id=?
                """)) {
            insert.setObject(1, eventId);
            insert.setString(2, type);
            insert.setString(3, VclCanonical.encode(payload));
            insert.setTimestamp(4, Timestamp.from(now));
            insert.setObject(5, alert);
            insert.setObject(6, worker.account());
            insert.setObject(7, device);
            insert.setString(8, worker.rule().toString());
            require(insert.executeUpdate() == 1, "ALERT_PROPOSAL_SCOPE_CHANGED");
        }
    }

    private static UUID insertAlert(Connection connection, VclWorkerAssertion.Scope worker, UUID device,
            WindowAlertProposal proposal, String dedup, java.time.Instant now) throws SQLException {
        UUID alert = UUID.randomUUID();
        try (var insert = connection.prepareStatement("""
                INSERT INTO alerts(id,account_id,device_id,rule_id,calculation_window_start,
                    calculation_window_end,calculation_input_hash,deduplication_key,alert_type,title,description,
                    severity,status,created_at)
                VALUES (?,?,?,?,?,?,?,?, 'CONFIGURABLE_RULE','Rule condition matched','A finalized rule window matched.',?,'OPEN',?)
                """)) {
            insert.setObject(1, alert);
            insert.setObject(2, worker.account());
            insert.setObject(3, device);
            insert.setString(4, worker.rule().toString());
            insert.setTimestamp(5, Timestamp.from(proposal.start()));
            insert.setTimestamp(6, Timestamp.from(proposal.end()));
            insert.setString(7, proposal.text("measureDigest"));
            insert.setString(8, dedup);
            insert.setString(9, proposal.severity().name());
            insert.setTimestamp(10, Timestamp.from(now));
            insert.executeUpdate();
        }
        return alert;
    }

    private static UUID appendCreatedEvent(Connection connection, UUID alert, UUID device, UUID rule,
            WindowAlertProposal proposal, java.time.Instant now) throws SQLException {
        UUID eventId = UUID.randomUUID();
        String type = "CONFIGURABLE_RULE_MATCHED";
        var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("eventId", eventId.toString()).put("alertId", alert.toString()).putNull("actorId")
                .put("action", type).put("timestamp", now.toString()).put("status", "OPEN").putNull("reason");
        var details = payload.putObject("context").put("deviceId", device.toString()).put("ruleId", rule.toString())
                .put("calculationWindowStart", proposal.start().toString()).put("calculationWindowEnd", proposal.end().toString())
                .putObject("details");
        if (proposal.prefix()) details.put("measuredThrough", proposal.effectEnd().toString());
        try (var insert = connection.prepareStatement("""
                INSERT INTO alert_events(id,alert_id,event_type,payload_json,created_at) VALUES (?,?,?,?,?)
                """)) {
            insert.setObject(1, eventId);
            insert.setObject(2, alert);
            insert.setString(3, type);
            insert.setString(4, VclCanonical.encode(payload));
            insert.setTimestamp(5, Timestamp.from(now));
            insert.executeUpdate();
        }
        return eventId;
    }

    @Transactional(readOnly = true)
    public Receipt reconcile(VclWorkerAssertion.Scope worker, UUID proposalId, byte[] bytes) {
        var proposal = scopedProposal(worker, proposalId, bytes);
        return jdbc.execute((ConnectionCallback<Receipt>) connection -> {
            try (var owner = connection.prepareStatement("""
                    SELECT id FROM cfg_automation_grant WHERE id=? AND account_id=? AND actor_id=? AND rule_id=?
                    """)) {
                owner.setObject(1, worker.grant());
                owner.setObject(2, worker.account());
                owner.setObject(3, worker.actor());
                owner.setObject(4, worker.rule());
                try (var rows = owner.executeQuery()) {
                    require(rows.next(), "ALERT_EFFECT_RECEIPT_UNAVAILABLE");
                }
            }
            var existing = receipt(connection, worker.account(), proposalId);
            require(existing != null, "ALERT_EFFECT_RECEIPT_UNAVAILABLE");
            require(existing.proposalDigest().equals(proposal.digest()), "ALERT_PROPOSAL_CONFLICT");
            return existing;
        });
    }

    public record Settlement(UUID proposalId, String proposalDigest, UUID alertId, UUID receiptId, String outcome) { }

    @Transactional
    public Settlement settle(VclWorkerAssertion.Scope worker, UUID proposalId, byte[] bytes) {
        var proposal = scopedProposal(worker, proposalId, bytes);
        return jdbc.execute((ConnectionCallback<Settlement>) connection -> {
            lockAccount(connection, worker.account());
            try (var grant = connection.prepareStatement("""
                    SELECT epoch,status,expires_at,definition_digest FROM cfg_automation_grant
                    WHERE id=? AND account_id=? AND actor_id=? AND rule_id=? FOR UPDATE
                    """)) {
                grant.setObject(1, worker.grant());
                grant.setObject(2, worker.account());
                grant.setObject(3, worker.actor());
                grant.setObject(4, worker.rule());
                try (var rows = grant.executeQuery()) {
                    require(rows.next(), "ALERT_EFFECT_RECEIPT_UNAVAILABLE");
                    var existing = receipt(connection, worker.account(), proposalId);
                    if (existing != null) {
                        require(existing.proposalDigest().equals(proposal.digest()), "ALERT_PROPOSAL_CONFLICT");
                        return new Settlement(proposalId, proposal.digest(), existing.alertId(), existing.receiptId(), "RECORDED");
                    }
                    long epoch = rows.getLong(1);
                    String status = rows.getString(2);
                    var expiry = rows.getObject(3, OffsetDateTime.class).toInstant();
                    String definition = rows.getString(4);
                    String digest = VclCanonical.sha256("AUTOMATION_GRANT/1:" + worker.grant() + ":" + worker.account()
                            + ":" + worker.actor() + ":" + worker.rule() + ":" + proposal.grantEpoch() + ":ACTIVE:" + definition);
                    require(digest.equals(proposal.text("grantDigest")), "GRANT_CHANGED");
                    boolean revoked = "REVOKED".equals(status) && epoch > proposal.grantEpoch();
                    boolean exactActive = "ACTIVE".equals(status) && epoch == proposal.grantEpoch();
                    var now = OwnerDatabaseClock.now(connection);
                    boolean closed = revoked || exactActive && (!now.isBefore(expiry)
                            || policyExpired(connection, worker, proposal, definition, now));
                    require(closed, "ALERT_EFFECT_RECEIPT_UNAVAILABLE");
                    // The terminal receipt identifies the proposal, independent of which authority closed first.
                    String identity = "VCL_ALERT_GRANT_CLOSED/1\n" + worker.account() + "\n" + proposalId + "\n" + proposal.digest();
                    UUID receiptId = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return new Settlement(proposalId, proposal.digest(), null, receiptId, "GRANT_CLOSED");
                }
            }
        });
    }

    private static boolean policyExpired(Connection connection, VclWorkerAssertion.Scope worker,
            WindowAlertProposal proposal, String grantDefinition, java.time.Instant now) throws SQLException {
        UUID id = proposal.uuid("policy");
        try (var query = connection.prepareStatement("""
                SELECT expires_at,definition_digest FROM cfg_evidence_policy
                WHERE id=? AND account_id=? AND actor_id=? AND rule_id=? AND grant_id=?
                    AND grant_epoch=? AND grant_definition_digest=? FOR UPDATE
                """)) {
            query.setObject(1, id);
            query.setObject(2, worker.account());
            query.setObject(3, worker.actor());
            query.setObject(4, worker.rule());
            query.setObject(5, worker.grant());
            query.setLong(6, proposal.grantEpoch());
            query.setString(7, grantDefinition);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return false;
                var expiry = rows.getObject(1, OffsetDateTime.class).toInstant();
                return !now.isBefore(expiry) && proposal.text("policyDigest").equals(policySnapshotDigest(worker,
                        id, Long.toString(proposal.grantEpoch()), grantDefinition, expiry, rows.getString(2)));
            }
        }
    }

    private static String policySnapshotDigest(VclWorkerAssertion.Scope worker, UUID id, String grantEpoch,
            String grantDefinition, java.time.Instant expiry, String definition) {
        return VclCanonical.sha256("EVIDENCE_POLICY/1:" + id + ":" + worker.account() + ":" + worker.actor()
                + ":" + worker.rule() + ":" + worker.grant() + ":" + grantEpoch + ":" + grantDefinition
                + ":" + expiry + ":" + definition);
    }

    private static WindowAlertProposal scopedProposal(VclWorkerAssertion.Scope worker, UUID proposalId, byte[] bytes) {
        var proposal = WindowAlertProposal.parse(bytes);
        require(proposalId.equals(worker.target()) && worker.account().equals(proposal.uuid("account"))
                && worker.rule().equals(proposal.uuid("rule")) && worker.epoch().equals(proposal.uuid("epoch"))
                && worker.grant().equals(proposal.uuid("grant")), "ALERT_PROPOSAL_SCOPE_CHANGED");
        return proposal;
    }

    private static VclWorkerAssertion.Scope retarget(VclWorkerAssertion.Scope scope, UUID target) {
        return new VclWorkerAssertion.Scope(scope.actor(), scope.account(), scope.rule(), scope.epoch(), scope.grant(),
                target, scope.operation(), scope.requestDigest());
    }

    private static void lockAuthority(Connection connection, VclWorkerAssertion.Scope scope,
            AutomationGrantReviews.GrantView grant, EvidencePolicyReviews.PolicyView policy) throws SQLException {
        lockAccount(connection, scope.account());
        try (var lock = connection.prepareStatement("""
                SELECT g.expires_at,p.expires_at FROM cfg_automation_grant g JOIN cfg_evidence_policy p
                    ON p.account_id=g.account_id AND p.grant_id=g.id AND p.grant_epoch=g.epoch
                    AND p.grant_definition_digest=g.definition_digest
                WHERE g.id=? AND g.account_id=? AND g.actor_id=? AND g.rule_id=? AND g.epoch=? AND g.status='ACTIVE'
                    AND g.definition_digest=? AND p.id=? AND p.actor_id=g.actor_id AND p.rule_id=g.rule_id
                    AND p.definition_digest=? FOR UPDATE
                """)) {
            lock.setObject(1, grant.id());
            lock.setObject(2, scope.account());
            lock.setObject(3, scope.actor());
            lock.setObject(4, scope.rule());
            lock.setLong(5, Long.parseLong(grant.epoch()));
            lock.setString(6, grant.definitionDigest());
            lock.setObject(7, policy.id());
            lock.setString(8, policy.definitionDigest());
            try (var rows = lock.executeQuery()) {
                require(rows.next(), "AUTOMATION_GRANT_INACTIVE");
                var now = OwnerDatabaseClock.now(connection);
                require(rows.getObject(1, OffsetDateTime.class).toInstant().equals(grant.expiresAt())
                        && rows.getObject(2, OffsetDateTime.class).toInstant().equals(policy.expiresAt())
                        && now.isBefore(grant.expiresAt()) && now.isBefore(policy.expiresAt()), "AUTOMATION_GRANT_INACTIVE");
            }
        }
    }

    private static void lockAccount(Connection connection, UUID account) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE")) {
            lock.setObject(1, account);
            try (var rows = lock.executeQuery()) {
                require(rows.next(), "OWNER_ACCESS_REVOKED");
            }
        }
    }

    private record Occurrence(UUID alert) { }

    private static Occurrence existingOccurrence(Connection connection, UUID account, String dedup) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT alert_id FROM cfg_rule_alert_occurrence_outcome WHERE account_id=? AND deduplication_key=?
                """)) {
            query.setObject(1, account);
            query.setString(2, dedup);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Occurrence(rows.getObject(1, UUID.class)) : null;
            }
        }
    }

    private static void recordOccurrence(Connection connection, UUID account, String dedup, Occurrence occurrence)
            throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO cfg_rule_alert_occurrence_outcome(account_id,deduplication_key,alert_id) VALUES (?,?,?)
                """)) {
            insert.setObject(1, account);
            insert.setString(2, dedup);
            insert.setObject(3, occurrence.alert());
            insert.executeUpdate();
        }
    }

    private static Receipt receipt(Connection connection, UUID account, UUID proposalId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT proposal_digest,original_alert_id,receipt_id FROM cfg_rule_alert_effect_receipt WHERE account_id=? AND proposal_id=?
                """)) {
            query.setObject(1, account);
            query.setObject(2, proposalId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Receipt(proposalId, rows.getString(1), rows.getObject(2, UUID.class),
                        rows.getObject(3, UUID.class)) : null;
            }
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
