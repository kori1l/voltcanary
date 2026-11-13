package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private byte[] bytes() { return VclCanonical.encode(proposal).getBytes(StandardCharsets.UTF_8); }
    private void assertCode(String code) {
        assertEquals(code, assertThrows(VclException.class, () -> effects.settle(worker, proposalId, bytes())).code());
    }
}
