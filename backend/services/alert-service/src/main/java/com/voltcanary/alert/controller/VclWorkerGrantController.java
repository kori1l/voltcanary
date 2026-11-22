package com.voltcanary.alert.controller;

import com.voltcanary.alert.config.VclWorkerIdentityProperties;
import com.voltcanary.alert.configurable.application.AutomationGrantReviews;
import com.voltcanary.alert.configurable.application.EvidencePolicyReviews;
import com.voltcanary.common.security.VclWorkerAssertion;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Machine-only grant inspection; the owner renews scope and grant state before returning. */
@RestController
public final class VclWorkerGrantController {

    private final AutomationGrantReviews grants;
    private final EvidencePolicyReviews policies;
    private final VclWorkerAssertion verifier;

    public VclWorkerGrantController(
        AutomationGrantReviews grants,
        EvidencePolicyReviews policies,
        VclWorkerIdentityProperties identity
    ) {
        this.grants = grants;
        this.policies = policies;
        verifier = identity.forAudience("alert-service");
    }

    @GetMapping("/internal/vcl/evidence-policies/{id}")
    public EvidencePolicyReviews.PolicyView policy(
        @RequestHeader(value = "X-VCL-Worker-Assertion", required = false) String assertion,
        @PathVariable UUID id,
        @RequestParam long revision
    ) {
        if (verifier == null || revision != 1) throw denied();
        var path = "/internal/vcl/evidence-policies/" + id + "?revision=" + revision;
        VclWorkerAssertion.Scope scope;
        try {
            scope = verifier.verify(
                assertion,
                "EVIDENCE_POLICY",
                id,
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])
            );
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
        return policies.machinePolicy(scope, id);
    }

    @GetMapping("/internal/vcl/grants/{id}")
    public AutomationGrantReviews.GrantView grant(
        @RequestHeader(value = "X-VCL-Worker-Assertion", required = false) String assertion,
        @PathVariable UUID id,
        @RequestParam long grantEpoch
    ) {
        if (verifier == null) throw denied();
        var path = "/internal/vcl/grants/" + id + "?grantEpoch=" + grantEpoch;
        VclWorkerAssertion.Scope scope;
        try {
            scope = verifier.verify(
                assertion,
                "GRANT_DESCRIPTION",
                id,
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])
            );
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
        return grants.machineGrant(scope, grantEpoch);
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
