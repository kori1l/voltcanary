package com.voltcanary.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.repository.JdbcLoginProtectionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class LoginProtectionServiceTest {

    private DriverManagerDataSource sharedDataSource;

    private LoginProtectionService protection(IdentityAuthProperties properties, Clock clock) {
        properties.getLogin().setTrackedKeyLimit(4);
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V012__shared_login_protection.sql")
            );
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
        sharedDataSource = dataSource;
        return new LoginProtectionService(
            properties,
            new JdbcLoginProtectionStore(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                properties,
                clock
            )
        );
    }

    @Test
    void independentInstancesShareAttemptsAndFailures() {
        var properties = new IdentityAuthProperties();
        properties.getLogin().setMaxAttemptsPerEmail(1);
        var clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        var first = protection(properties, clock);
        var second = new LoginProtectionService(
            properties,
            new JdbcLoginProtectionStore(
                new JdbcTemplate(sharedDataSource),
                new DataSourceTransactionManager(sharedDataSource),
                properties,
                clock
            )
        );
        first.checkAllowed("shared@example.test", null);
        assertThatThrownBy(() -> second.checkAllowed("shared@example.test", null)).isInstanceOf(
            AuthenticationFailedException.class
        );
        first.recordFailure("blocked@example.test");
        for (
            int index = 1;
            index < properties.getLogin().getFailedAttemptsBeforeLockout();
            index++
        ) {
            second.recordFailure("blocked@example.test");
        }
        assertThatThrownBy(() -> first.checkAllowed("blocked@example.test", null)).isInstanceOf(
            AuthenticationFailedException.class
        );
    }

    @Test
    void capacityCannotEvictAnActiveLimitAndExpiredKeysReleaseCapacity() {
        var properties = new IdentityAuthProperties();
        properties.getLogin().setMaxAttemptsPerEmail(1);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var protection = protection(properties, clock);
        for (int index = 0; index < 4; index++) protection.checkAllowed("email-" + index, null);
        assertThatThrownBy(() -> protection.checkAllowed("new-email", null)).isInstanceOf(
            AuthenticationFailedException.class
        );
        assertThatThrownBy(() -> protection.checkAllowed("email-0", null)).isInstanceOf(
            AuthenticationFailedException.class
        );
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
        var protection = protection(properties, clock);
        for (int index = 0; index < 4; index++) protection.recordFailure("email-" + index);
        assertThatThrownBy(() -> protection.recordFailure("new-email")).isInstanceOf(
            AuthenticationFailedException.class
        );
        when(clock.instant()).thenReturn(now.plusSeconds(301));
        protection.recordFailure("new-email");
        protection.recordFailure("email-0");
        protection.checkAllowed("email-0", null);
        protection.recordFailure("email-0");
        assertThatThrownBy(() -> protection.checkAllowed("email-0", null)).isInstanceOf(
            AuthenticationFailedException.class
        );
    }
}
