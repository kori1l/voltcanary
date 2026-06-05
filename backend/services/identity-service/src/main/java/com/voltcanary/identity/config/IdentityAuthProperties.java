package com.voltcanary.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class IdentityAuthProperties {
    private long passwordResetTokenTtlSeconds = 900;
    private long emailVerificationTokenTtlSeconds = 86_400;
    private Login login = new Login();

    public long getPasswordResetTokenTtlSeconds() { return passwordResetTokenTtlSeconds; }
    public void setPasswordResetTokenTtlSeconds(long passwordResetTokenTtlSeconds) { this.passwordResetTokenTtlSeconds = passwordResetTokenTtlSeconds; }
    public long getEmailVerificationTokenTtlSeconds() { return emailVerificationTokenTtlSeconds; }
    public void setEmailVerificationTokenTtlSeconds(long emailVerificationTokenTtlSeconds) { this.emailVerificationTokenTtlSeconds = emailVerificationTokenTtlSeconds; }
    public Login getLogin() { return login; }
    public void setLogin(Login login) { this.login = login; }

    public static class Login {
        private int maxAttemptsPerEmail = 10;
        private int maxAttemptsPerIp = 50;
        private long attemptWindowSeconds = 60;
        private int failedAttemptsBeforeLockout = 5;
        private long lockoutSeconds = 300;

        public int getMaxAttemptsPerEmail() { return maxAttemptsPerEmail; }
        public void setMaxAttemptsPerEmail(int maxAttemptsPerEmail) { this.maxAttemptsPerEmail = maxAttemptsPerEmail; }
        public int getMaxAttemptsPerIp() { return maxAttemptsPerIp; }
        public void setMaxAttemptsPerIp(int maxAttemptsPerIp) { this.maxAttemptsPerIp = maxAttemptsPerIp; }
        public long getAttemptWindowSeconds() { return attemptWindowSeconds; }
        public void setAttemptWindowSeconds(long attemptWindowSeconds) { this.attemptWindowSeconds = attemptWindowSeconds; }
        public int getFailedAttemptsBeforeLockout() { return failedAttemptsBeforeLockout; }
        public void setFailedAttemptsBeforeLockout(int failedAttemptsBeforeLockout) { this.failedAttemptsBeforeLockout = failedAttemptsBeforeLockout; }
        public long getLockoutSeconds() { return lockoutSeconds; }
        public void setLockoutSeconds(long lockoutSeconds) { this.lockoutSeconds = lockoutSeconds; }
    }
}
