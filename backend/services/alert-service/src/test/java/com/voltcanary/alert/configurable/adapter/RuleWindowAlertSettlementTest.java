package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class RuleWindowAlertSettlementTest {
    private JdbcTemplate jdbc;
    private RuleWindowAlertEffects effects;
    private ObjectNode proposal;
    private VclWorkerAssertion.Scope worker;
    private final UUID actor = UUID.randomUUID(), proposalId = UUID.randomUUID();

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        effects = new RuleWindowAlertEffects(jdbc, null, null, null, null, null, null, null);
        proposal = WindowAlertProposalTest.proposal();
        var parsed = WindowAlertProposal.parse(bytes());
        worker = new VclWorkerAssertion.Scope(actor, parsed.uuid("account"), parsed.uuid("rule"), parsed.uuid("epoch"),
                parsed.uuid("grant"), proposalId, "WINDOW_ALERT_SETTLEMENT", "a".repeat(64));
        UUID reservation = UUID.randomUUID(), review = UUID.randomUUID();
        var expires = Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO cfg_rule_owner_fence(account_id) VALUES(?)", worker.account());
        jdbc.update("""
                INSERT INTO cfg_rule_review_reservation(id,account_id,actor_id,rule_id,receipt_id,reservation_key_hash,
                    reservation_key,request_digest,output_ids_json,status,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED',?)
                """, reservation, worker.account(), actor, worker.rule(), UUID.randomUUID(), "a".repeat(64), "test", "a".repeat(64), "{}", expires);
        jdbc.update("""
                INSERT INTO cfg_grant_review(id,account_id,actor_id,rule_review_id,rule_id,grant_id,receipt_id,
                    reservation_key_hash,reservation_key,reservation_digest,status,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,'SUCCEEDED',?)
                """, review, worker.account(), actor, reservation, worker.rule(), worker.grant(), UUID.randomUUID(),
                "a".repeat(64), "test", "a".repeat(64), expires);
        jdbc.update("""
                INSERT INTO cfg_automation_grant(id,account_id,actor_id,rule_id,review_id,canonical_definition,canonical_pins,
                    definition_digest,epoch,status,expires_at) VALUES(?,?,?,?,?,'{}','[]',?,1,'ACTIVE',?)
                """, worker.grant(), worker.account(), actor, worker.rule(), review, "b".repeat(64), expires);
        proposal.put("grantDigest", VclCanonical.sha256("AUTOMATION_GRANT/1:" + worker.grant() + ":" + worker.account()
                + ":" + actor + ":" + worker.rule() + ":1:ACTIVE:" + "b".repeat(64)));
    }

    @Test void activeGrantCannotBeAcknowledgedAsClosed() {
        assertCode("ALERT_EFFECT_RECEIPT_UNAVAILABLE");
        jdbc.update("UPDATE cfg_automation_grant SET status='REVOKED' WHERE id=?", worker.grant());
        assertCode("ALERT_EFFECT_RECEIPT_UNAVAILABLE");
    }

    @Test void revocationProducesAStableReadOnlySettlementForTheExactProposal() {
        jdbc.update("UPDATE cfg_automation_grant SET status='REVOKED',epoch=2 WHERE id=?", worker.grant());
        var first = effects.settle(worker, proposalId, bytes());
        assertEquals("GRANT_CLOSED", first.outcome());
        assertNull(first.alertId());
        assertEquals(first, effects.settle(worker, proposalId, bytes()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_rule_alert_effect_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM alerts", Integer.class));
        proposal.put("grantDigest", "c".repeat(64));
        assertCode("GRANT_CHANGED");
    }

    @Test void expiredExactGrantIsTerminalWithoutAnExternalOwnerRead() {
        jdbc.update("UPDATE cfg_automation_grant SET expires_at=? WHERE id=?", Instant.EPOCH.atOffset(ZoneOffset.UTC), worker.grant());
        assertEquals("GRANT_CLOSED", effects.settle(worker, proposalId, bytes()).outcome());
    }

    @Test void anExistingReceiptWinsAndRejectsConflictingProposalBytes() {
        UUID receipt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cfg_rule_alert_effect_receipt(account_id,proposal_id,proposal_digest,alert_id,original_alert_id,receipt_id)
                VALUES(?,?,?,NULL,NULL,?)
                """, worker.account(), proposalId, VclCanonical.sha256(VclCanonical.encode(proposal)), receipt);
        var result = effects.settle(worker, proposalId, bytes());
        assertEquals("RECORDED", result.outcome());
        assertEquals(receipt, result.receiptId());
        proposal.put("severity", "CRITICAL");
        assertCode("ALERT_PROPOSAL_CONFLICT");
    }

    @Test void changedSourceBeforeCommitRollsBackAlertReceiptAndEvidence() {
        var fixture = effectFixture();
        org.mockito.Mockito.when(fixture.sources().verifyWindowSource(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(fixture.device())
                .thenThrow(new VclException("ALERT_EVIDENCE_CHANGED", ""));
        assertEquals("ALERT_EVIDENCE_CHANGED", assertThrows(VclException.class,
                () -> apply(fixture.effects())).code());
        assertNoEffect();
    }

    @Test void freshMachineViewCannotOverrideRevocationAtTheWriteFence() {
        var fixture = effectFixture();
        jdbc.update("UPDATE cfg_automation_grant SET status='REVOKED',epoch=2 WHERE id=?", worker.grant());
        assertEquals("AUTOMATION_GRANT_INACTIVE", assertThrows(VclException.class,
                () -> apply(fixture.effects())).code());
        org.mockito.Mockito.verifyNoInteractions(fixture.sources());
        assertNoEffect();
    }

    @Test void competingDeliveriesReturnOneReceiptWithoutSchedulingNotifications() throws Exception {
        var fixture = effectFixture();
        org.mockito.Mockito.when(fixture.sources().verifyWindowSource(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(fixture.device());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<RuleWindowAlertEffects.Receipt> deliver = () -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return apply(fixture.effects());
            };
            var first = executor.submit(deliver);
            var second = executor.submit(deliver);
            var receipt = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(receipt.alertId());
            assertEquals(receipt, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_rule_alert_effect_receipt", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_rule_alert_private_evidence", Integer.class));
        org.mockito.Mockito.verifyNoInteractions(fixture.notifications());
    }

    private void assertNoEffect() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM alerts", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_rule_alert_effect_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_rule_alert_private_evidence", Integer.class));
    }

    private RuleWindowAlertEffects.Receipt apply(RuleWindowAlertEffects writer) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        return transaction.execute(status -> writer.apply(worker, proposalId, bytes()));
    }

    private record EffectFixture(RuleWindowAlertEffects effects, WorkerGrantScopeAuthority sources, UUID device,
            com.voltcanary.alert.notification.port.AlertNotificationPort notifications) { }

    private EffectFixture effectFixture() {
        var grantOwner = org.mockito.Mockito.mock(com.voltcanary.alert.configurable.application.AutomationGrantReviews.class);
        var policyOwner = org.mockito.Mockito.mock(com.voltcanary.alert.configurable.application.EvidencePolicyReviews.class);
        var sources = org.mockito.Mockito.mock(WorkerGrantScopeAuthority.class);
        var suppression = org.mockito.Mockito.mock(com.voltcanary.alert.service.AlertMaintenanceSuppressionService.class);
        var deletion = org.mockito.Mockito.mock(com.voltcanary.alert.service.DeviceHistoryDeletion.class);
        var preferences = org.mockito.Mockito.mock(com.voltcanary.alert.notification.application.NotificationPreferenceService.class);
        var notifications = org.mockito.Mockito.mock(com.voltcanary.alert.notification.port.AlertNotificationPort.class);
        var parsed = WindowAlertProposal.parse(bytes());
        UUID policy = parsed.uuid("policy"), review = UUID.randomUUID(), device = UUID.randomUUID();
        var expiry = Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var grantDefinition = new com.voltcanary.alert.configurable.domain.AutomationGrantScope(
                List.of(new com.voltcanary.alert.configurable.domain.AutomationGrantScope.Resource(
                        parsed.binding(), parsed.schemaRevision(), List.of(UUID.randomUUID()))), List.of(), expiry).json();
        var policyDefinition = new com.voltcanary.alert.configurable.domain.EvidencePolicyDefinition(1, 4).json();
        String grantDigest = VclCanonical.sha256(VclCanonical.encode(grantDefinition));
        String policyDigest = VclCanonical.sha256(VclCanonical.encode(policyDefinition));
        jdbc.update("UPDATE cfg_automation_grant SET canonical_definition=?,definition_digest=?,expires_at=? WHERE id=?",
                VclCanonical.encode(grantDefinition), grantDigest, expiry.atOffset(ZoneOffset.UTC), worker.grant());
        jdbc.update("""
                INSERT INTO cfg_evidence_policy_review(id,account_id,actor_id,grant_id,rule_id,grant_epoch,grant_definition_digest,
                    policy_id,receipt_id,key_hash,reservation_key,status,expires_at) VALUES(?,?,?,?,?,1,?,?,?,?,?,'SUCCEEDED',?)
                """, review, worker.account(), actor, worker.grant(), worker.rule(), grantDigest, policy, UUID.randomUUID(),
                "a".repeat(64), "test", expiry.atOffset(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO cfg_evidence_policy(id,account_id,actor_id,rule_id,grant_id,grant_epoch,grant_definition_digest,
                    review_id,canonical_definition,definition_digest,expires_at) VALUES(?,?,?,?,?,1,?,?,?,?,?)
                """, policy, worker.account(), actor, worker.rule(), worker.grant(), grantDigest, review,
                VclCanonical.encode(policyDefinition), policyDigest, expiry.atOffset(ZoneOffset.UTC));
        proposal.put("grantDigest", VclCanonical.sha256("AUTOMATION_GRANT/1:" + worker.grant() + ":" + worker.account()
                + ":" + actor + ":" + worker.rule() + ":1:ACTIVE:" + grantDigest));
        proposal.put("policyDigest", VclCanonical.sha256("EVIDENCE_POLICY/1:" + policy + ":" + worker.account() + ":" + actor
                + ":" + worker.rule() + ":" + worker.grant() + ":1:" + grantDigest + ":" + expiry + ":" + policyDigest));
        var grant = new com.voltcanary.alert.configurable.application.AutomationGrantReviews.GrantView(worker.grant(),
                worker.account(), actor, worker.rule(), "source-native-fields-grant-1", "1", "ACTIVE", expiry,
                grantDigest, grantDefinition, List.of(), List.of());
        var evidence = new com.voltcanary.alert.configurable.application.EvidencePolicyReviews.PolicyView(policy,
                worker.account(), actor, worker.rule(), worker.grant(), "1", grantDigest, "private-reference-evidence-1",
                1, "ACTIVE", true, expiry, policyDigest, policyDefinition);
        org.mockito.Mockito.when(grantOwner.machineGrant(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(grant);
        org.mockito.Mockito.when(policyOwner.machinePolicy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(policy)))
                .thenReturn(evidence);
        org.mockito.Mockito.when(deletion.writable(device)).thenReturn(true);
        return new EffectFixture(new RuleWindowAlertEffects(jdbc, grantOwner, policyOwner, sources, suppression, deletion,
                preferences, notifications), sources, device, notifications);
    }

    private byte[] bytes() { return VclCanonical.encode(proposal).getBytes(StandardCharsets.UTF_8); }
    private void assertCode(String code) {
        assertEquals(code, assertThrows(VclException.class, () -> effects.settle(worker, proposalId, bytes())).code());
    }
}
