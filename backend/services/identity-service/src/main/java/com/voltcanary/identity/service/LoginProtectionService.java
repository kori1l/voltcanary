package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LoginProtectionService {

    private final LoginProtectionStore store;
    private final IdentityAuthProperties.Login policy;

    public LoginProtectionService(IdentityAuthProperties properties, LoginProtectionStore store) {
        this.store = store;
        this.policy = properties.getLogin();
    }

    public void checkAllowed(String email, String clientIp) {
        boolean allowed = store.update(entries -> {
            Instant now = entries.now();
            var failures = entries.get("failure:" + email);
            if (
                failures != null &&
                failures.lockedUntil() != null &&
                failures.lockedUntil().isAfter(now)
            ) {
                return false;
            }
            if (failures != null && failures.lockedUntil() != null) {
                entries.remove("failure:" + email);
            }
            boolean emailAllowed = recordAttempt(
                entries,
                "email:" + email,
                policy.getMaxAttemptsPerEmail()
            );
            boolean ipAllowed =
                clientIp == null ||
                clientIp.isBlank() ||
                recordAttempt(entries, "ip:" + clientIp, policy.getMaxAttemptsPerIp());
            return emailAllowed && ipAllowed;
        });
        if (!allowed) {
            throw new AuthenticationFailedException("Invalid email or password");
        }
    }

    public void recordFailure(String email) {
        store.update(entries -> {
            Instant now = entries.now();
            var previous = entries.get("failure:" + email);
            int failures = Math.min(
                policy.getFailedAttemptsBeforeLockout(),
                previous == null ? 1 : previous.failures() + 1
            );
            Instant lockedUntil =
                failures >= policy.getFailedAttemptsBeforeLockout()
                    ? now.plusSeconds(policy.getLockoutSeconds())
                    : null;
            entries.put(
                "failure:" + email,
                new LoginProtectionStore.State(
                    List.of(),
                    failures,
                    lockedUntil,
                    now.plusSeconds(
                        Math.max(policy.getAttemptWindowSeconds(), policy.getLockoutSeconds())
                    )
                )
            );
            return null;
        });
    }

    public void recordSuccess(String email) {
        store.update(entries -> {
            entries.remove("failure:" + email);
            return null;
        });
    }

    private boolean recordAttempt(LoginProtectionStore.Entries entries, String key, int maximum) {
        Instant now = entries.now();
        Instant cutoff = now.minusSeconds(policy.getAttemptWindowSeconds());
        var previous = entries.get(key);
        var attempts = new ArrayList<Instant>();
        if (previous != null) {
            previous
                .attempts()
                .stream()
                .filter(attempt -> !attempt.isBefore(cutoff))
                .forEach(attempts::add);
        }
        if (attempts.size() >= maximum) {
            return false;
        }
        attempts.add(now);
        entries.put(
            key,
            new LoginProtectionStore.State(
                attempts,
                0,
                null,
                now.plusSeconds(policy.getAttemptWindowSeconds())
            )
        );
        return true;
    }
}
