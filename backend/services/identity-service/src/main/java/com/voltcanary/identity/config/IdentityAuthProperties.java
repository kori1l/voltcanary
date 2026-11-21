package com.voltcanary.identity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class IdentityAuthProperties {
    private long passwordResetTokenTtlSeconds = 900;
    private long emailVerificationTokenTtlSeconds = 86_400;
    private Login login = new Login();

    public static class Login {
        @Getter
        @Setter
        private int maxAttemptsPerEmail = 10;
        @Getter
        @Setter
        private int maxAttemptsPerIp = 50;
        @Getter
        @Setter
        private long attemptWindowSeconds = 60;
        @Getter
        @Setter
        private int failedAttemptsBeforeLockout = 5;
        @Getter
        @Setter
        private long lockoutSeconds = 300;

    }
}
