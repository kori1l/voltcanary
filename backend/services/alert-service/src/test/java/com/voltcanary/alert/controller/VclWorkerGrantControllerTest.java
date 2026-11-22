package com.voltcanary.alert.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.voltcanary.alert.config.VclWorkerIdentityProperties;
import com.voltcanary.alert.configurable.application.AutomationGrantReviews;
import com.voltcanary.alert.configurable.application.EvidencePolicyReviews;
import com.voltcanary.common.security.VclWorkerAssertion;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VclWorkerGrantControllerTest {

    @Test
    void exactGrantAndEpochRequireDedicatedMachineAudience() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var identity = new VclWorkerIdentityProperties(
            Base64.getEncoder().encodeToString(key),
            "worker-1"
        );
        var signer = new VclWorkerAssertion(key, "worker-1", "alert-service", Clock.systemUTC());
        var grant = UUID.randomUUID();
        var path = "/internal/vcl/grants/" + grant + "?grantEpoch=3";
        var scope = new VclWorkerAssertion.Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            grant,
            grant,
            "GRANT_DESCRIPTION",
            VclWorkerAssertion.requestDigest("GET", path, new byte[0])
        );
        var token = signer.sign(scope, 30);
        var owner = mock(AutomationGrantReviews.class);
        var response = mock(AutomationGrantReviews.GrantView.class);
        when(owner.machineGrant(any(), eq(3L))).thenReturn(response);
        var policies = mock(EvidencePolicyReviews.class);
        var controller = new VclWorkerGrantController(owner, policies, identity);
        assertSame(response, controller.grant(token, grant, 3));
        verify(owner).machineGrant(eq(scope), eq(3L));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.grant(token, grant, 4)
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.grant(token, UUID.randomUUID(), 3)
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.grant(null, grant, 3)
        );
        var policy = UUID.randomUUID();
        var policyPath = "/internal/vcl/evidence-policies/" + policy + "?revision=1";
        var policyScope = new VclWorkerAssertion.Scope(
            scope.actor(),
            scope.account(),
            scope.rule(),
            scope.epoch(),
            grant,
            policy,
            "EVIDENCE_POLICY",
            VclWorkerAssertion.requestDigest("GET", policyPath, new byte[0])
        );
        var policyToken = signer.sign(policyScope, 30);
        var policyResponse = mock(EvidencePolicyReviews.PolicyView.class);
        when(policies.machinePolicy(any(), eq(policy))).thenReturn(policyResponse);
        assertSame(policyResponse, controller.policy(policyToken, policy, 1));
        verify(policies).machinePolicy(eq(policyScope), eq(policy));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.policy(policyToken, policy, 2)
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.policy(token, policy, 1)
        );
        var disabled = new VclWorkerGrantController(
            owner,
            policies,
            new VclWorkerIdentityProperties("", "worker-1")
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            disabled.grant(token, grant, 3)
        );
        verifyNoMoreInteractions(owner);
    }
}
