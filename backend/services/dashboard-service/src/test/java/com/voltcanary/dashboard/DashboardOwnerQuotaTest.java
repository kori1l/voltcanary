package com.voltcanary.dashboard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voltcanary.dashboard.application.DashboardAccess;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardOwnerQuotaTest {

    @Test
    void ownerOverrideDoesNotRaiseOtherOwnersAndStopsAtNewLimit() {
        var storage = mock(DashboardStorage.class);
        var owner = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
        var other = new DashboardActor(UUID.randomUUID(), owner.accountId());
        when(storage.activeLimit(owner)).thenReturn(50);
        when(storage.activeLimit(other)).thenReturn(10);
        when(storage.activeCount(owner)).thenReturn(10L);
        when(storage.activeCount(other)).thenReturn(10L);
        var access = new DashboardAccess(storage, null);
        access.quota(owner);
        assertThatThrownBy(() -> access.quota(other)).isInstanceOf(DashboardFailure.class);
        when(storage.activeCount(owner)).thenReturn(50L);
        assertThatThrownBy(() -> access.quota(owner)).isInstanceOf(DashboardFailure.class);
    }
}
