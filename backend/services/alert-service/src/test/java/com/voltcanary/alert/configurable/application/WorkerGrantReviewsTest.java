package com.voltcanary.alert.configurable.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.configurable.adapter.WorkerGrantScopeAuthority;
import com.voltcanary.alert.configurable.domain.AutomationGrant;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.configurable.port.GrantReviewStore;
import com.voltcanary.alert.configurable.port.GrantRevocationStore;
import com.voltcanary.alert.configurable.port.GrantScopeAuthority;
import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerGrantReviewsTest {
    @Test void machineReadRenewsExactGrantAndRejectsOwnerDriftAndConcurrentRevocation() {
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var rule = UUID.randomUUID();
        var grantId = UUID.randomUUID();
        var scope = new VclWorkerAssertion.Scope(actor, account, rule, UUID.randomUUID(), grantId, grantId,
                "GRANT_DESCRIPTION", "a".repeat(64));
        var definition = new AutomationGrantScope(List.of(new AutomationGrantScope.Resource(
                UUID.randomUUID(), 1, List.of(UUID.randomUUID()))), List.of(),
                Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        var canonical = VclCanonical.encode(definition.json());
        var pins = JsonNodeFactory.instance.arrayNode();
        pins.addObject().put("pin", "current");
        var grant = new AutomationGrant(grantId, account, actor, rule, UUID.randomUUID(), canonical,
                VclCanonical.encode(pins), VclCanonical.sha256(canonical), 3, "ACTIVE", definition.expiresAt());
        var revoked = new AutomationGrant(grantId, account, actor, rule, grant.reviewId(), canonical,
                VclCanonical.encode(pins), grant.definitionDigest(), 4, "REVOKED", definition.expiresAt());
        var accounts = mock(RuleAccountAuthority.class);
        var reviews = mock(GrantReviewStore.class);
        var workerScopes = mock(WorkerGrantScopeAuthority.class);
        when(accounts.account(actor)).thenReturn(account);
        when(reviews.grant(account, actor, grantId)).thenReturn(grant);
        when(workerScopes.validate(eq(scope), any())).thenReturn(pins);
        var service = new AutomationGrantReviews(accounts, mock(GrantScopeAuthority.class), reviews,
                mock(GrantRevocationStore.class), workerScopes);
        assertEquals("3", service.machineGrant(scope, 3).epoch());
        assertEquals("AUTOMATION_GRANT_INACTIVE", assertThrows(VclException.class,
                () -> service.machineGrant(scope, 2)).code());
        when(workerScopes.validate(eq(scope), any())).thenReturn(JsonNodeFactory.instance.arrayNode());
        assertEquals("GRANT_SCOPE_CHANGED", assertThrows(VclException.class,
                () -> service.machineGrant(scope, 3)).code());
        when(workerScopes.validate(eq(scope), any())).thenReturn(pins);
        when(reviews.grant(account, actor, grantId)).thenReturn(grant, revoked);
        assertEquals("GRANT_CHANGED", assertThrows(VclException.class,
                () -> service.machineGrant(scope, 3)).code());
        verify(workerScopes, times(3)).validate(eq(scope), any());
    }
}
