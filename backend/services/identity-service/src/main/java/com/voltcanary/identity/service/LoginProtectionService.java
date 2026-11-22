package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LoginProtectionService {

    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final String GENERIC_LOGIN_FAILURE = "Invalid email or password";

    private final Map<String, AttemptWindow> attemptWindows = new ConcurrentHashMap<>();
    private final Map<String, FailedLoginState> failedLogins = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxAttemptsPerEmail;
    private final int maxAttemptsPerIp;
    private final long attemptWindowSeconds;
    private final int failedAttemptsBeforeLockout;
    private final long lockoutSeconds;
    @org.springframework.beans.factory.annotation.Autowired
    public LoginProtectionService(IdentityAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginProtectionService(IdentityAuthProperties properties, Clock clock) {
        this.maxAttemptsPerEmail = properties.getLogin().getMaxAttemptsPerEmail();
        this.maxAttemptsPerIp = properties.getLogin().getMaxAttemptsPerIp();
        this.attemptWindowSeconds = properties.getLogin().getAttemptWindowSeconds();
        this.failedAttemptsBeforeLockout = properties.getLogin().getFailedAttemptsBeforeLockout();
        this.lockoutSeconds = properties.getLogin().getLockoutSeconds();
        this.clock = clock;
    }

    public synchronized void checkAllowed(String email, String clientIp) {
        Instant now = clock.instant();
        FailedLoginState failedLoginState = failedLogins.get(email);
        if (failedLoginState != null && failedLoginState.isLocked(now)) {
            throw genericFailure();
        }
        if (failedLoginState != null && failedLoginState.lockExpired(now)) {
            failedLogins.remove(email, failedLoginState);
        }

        recordAttempt("email:" + email, maxAttemptsPerEmail, now);
        if (clientIp != null && !clientIp.isBlank()) {
            recordAttempt("ip:" + clientIp, maxAttemptsPerIp, now);
        }
    }

    public synchronized void recordFailure(String email) {
        Instant now = clock.instant();
        if (!failedLogins.containsKey(email) && failedLogins.size() >= MAX_TRACKED_KEYS) {
            failedLogins.entrySet().removeIf(entry -> entry.getValue().expired(now));
            if (failedLogins.size() >= MAX_TRACKED_KEYS) throw genericFailure();
        }
        failedLogins.compute(email, (key, current) -> {
            FailedLoginState state = current == null || current.expired(now) ? new FailedLoginState() : current;
            state.failedAttempts = Math.min(failedAttemptsBeforeLockout, state.failedAttempts + 1);
            state.idleUntil = now.plusSeconds(Math.max(attemptWindowSeconds, lockoutSeconds));
            if (state.failedAttempts >= failedAttemptsBeforeLockout) {
                state.lockedUntil = now.plusSeconds(lockoutSeconds);
            }
            return state;
        });
    }

    public synchronized void recordSuccess(String email) {
        failedLogins.remove(email);
    }

    private void recordAttempt(String key, int maxAttempts, Instant now) {
        if (!attemptWindows.containsKey(key) && attemptWindows.size() >= MAX_TRACKED_KEYS) {
            Instant cutoff = now.minusSeconds(attemptWindowSeconds);
            attemptWindows.entrySet().removeIf(entry -> entry.getValue().attempts.isEmpty()
                    || entry.getValue().attempts.peekLast().isBefore(cutoff));
            if (attemptWindows.size() >= MAX_TRACKED_KEYS) throw genericFailure();
        }
        AttemptWindow window = attemptWindows.computeIfAbsent(key, ignored -> new AttemptWindow());
        synchronized (window) {
            Instant oldestAllowed = now.minusSeconds(attemptWindowSeconds);
            while (!window.attempts.isEmpty() && window.attempts.peekFirst().isBefore(oldestAllowed)) {
                window.attempts.removeFirst();
            }
            if (window.attempts.size() >= maxAttempts) {
                throw genericFailure();
            }
            window.attempts.addLast(now);
        }
    }

    private AuthenticationFailedException genericFailure() {
        return new AuthenticationFailedException(GENERIC_LOGIN_FAILURE);
    }

    private static class AttemptWindow {
        private final Deque<Instant> attempts = new ArrayDeque<>();
    }

    private static class FailedLoginState {
        private int failedAttempts;
        private Instant lockedUntil;
        private Instant idleUntil;

        private boolean expired(Instant now) {
            return !isLocked(now) && idleUntil != null && !idleUntil.isAfter(now);
        }

        private boolean isLocked(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        private boolean lockExpired(Instant now) {
            return lockedUntil != null && !lockedUntil.isAfter(now);
        }
    }
}
