package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginProtectionServiceTest {
    @Test
    void capacityCannotEvictAnActiveLimitAndExpiredKeysReleaseCapacity() {
        var properties = new IdentityAuthProperties();
        properties.getLogin().setMaxAttemptsPerEmail(1);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var protection = new LoginProtectionService(properties, clock);
        for (int index = 0; index < 10_000; index++) protection.checkAllowed("email-" + index, null);
        assertThatThrownBy(() -> protection.checkAllowed("new-email", null)).isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> protection.checkAllowed("email-0", null)).isInstanceOf(AuthenticationFailedException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        protection.checkAllowed("new-email", null);
    }

    @Test
    void failureTrackingIsBoundedAndAnIdleFailureDoesNotLockForever() {
        var properties = new IdentityAuthProperties();
        properties.getLogin().setFailedAttemptsBeforeLockout(2);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var protection = new LoginProtectionService(properties, clock);
        for (int index = 0; index < 10_000; index++) protection.recordFailure("email-" + index);
        assertThatThrownBy(() -> protection.recordFailure("new-email")).isInstanceOf(AuthenticationFailedException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(301));
        protection.recordFailure("new-email");
        protection.recordFailure("email-0");
        protection.checkAllowed("email-0", null);
        protection.recordFailure("email-0");
        assertThatThrownBy(() -> protection.checkAllowed("email-0", null)).isInstanceOf(AuthenticationFailedException.class);
    }
}
