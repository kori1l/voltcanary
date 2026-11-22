package com.voltcanary.alert.configurable.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.voltcanary.alert.configurable.domain.EvidencePolicy;
import com.voltcanary.alert.configurable.port.EvidencePolicyStore;
import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerEvidencePolicyReviewsTest {

    @Test
    void policyRenewsGrantAndRejectsConcurrentPolicyRevocation() {
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var rule = UUID.randomUUID();
        var grantId = UUID.randomUUID();
        var policyId = UUID.randomUUID();
        var definition =
            "{\"mode\":\"PRIVATE_REFERENCES\",\"retentionDays\":1,\"maxObservationReferences\":1}";
        var policy = new EvidencePolicy(
            policyId,
            account,
            actor,
            rule,
            grantId,
            3,
            "a".repeat(64),
            UUID.randomUUID(),
            definition,
            VclCanonical.sha256(definition),
            Instant.now().plusSeconds(3600)
        );
        var worker = new VclWorkerAssertion.Scope(
            actor,
            account,
            rule,
            UUID.randomUUID(),
            grantId,
            policyId,
            "EVIDENCE_POLICY",
            "b".repeat(64)
        );
        var accounts = mock(RuleAccountAuthority.class);
        var grants = mock(AutomationGrantReviews.class);
        var store = mock(EvidencePolicyStore.class);
        when(accounts.account(actor)).thenReturn(account);
        when(store.policy(account, actor, policyId)).thenReturn(
            new EvidencePolicyStore.Inspection(policy, true)
        );
        when(grants.machineGrant(any(), eq(3L))).thenReturn(
            new AutomationGrantReviews.GrantView(
                grantId,
                account,
                actor,
                rule,
                "source-native-fields-grant-1",
                "3",
                "ACTIVE",
                policy.expiresAt(),
                policy.grantDefinitionDigest(),
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                List.of("ALERT"),
                List.of()
            )
        );
        var service = new EvidencePolicyReviews(accounts, grants, store);
        assertTrue(service.machinePolicy(worker, policyId).usable());
        when(store.policy(account, actor, policyId)).thenReturn(
            new EvidencePolicyStore.Inspection(policy, true),
            new EvidencePolicyStore.Inspection(policy, false)
        );
        assertEquals(
            "EVIDENCE_POLICY_INACTIVE",
            assertThrows(VclException.class, () -> service.machinePolicy(worker, policyId)).code()
        );
        verify(grants, times(2)).machineGrant(
            argThat(
                scope ->
                    scope.target().equals(grantId) &&
                    scope.actor().equals(actor) &&
                    scope.rule().equals(rule)
            ),
            eq(3L)
        );
    }
}
