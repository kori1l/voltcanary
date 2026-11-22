package com.voltcanary.identity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
@org.springframework.validation.annotation.Validated
public class IdentityAuthProperties {

    private long passwordResetTokenTtlSeconds = 900;
    private long emailVerificationTokenTtlSeconds = 86_400;
    private String mailEncryptionKey = "";
    private String mailFrom = "no-reply@voltcanary.local";

    @jakarta.validation.Valid
    private Login login = new Login();

    public static class Login {

        @Getter
        @Setter
        @jakarta.validation.constraints.Min(1)
        private int trackedKeyLimit = 20_000;

        @Getter
        @Setter
        @jakarta.validation.constraints.Min(1)
        @jakarta.validation.constraints.Max(1000)
        private int maxAttemptsPerEmail = 10;

        @Getter
        @Setter
        @jakarta.validation.constraints.Min(1)
        @jakarta.validation.constraints.Max(10000)
        private int maxAttemptsPerIp = 50;

        @Getter
        @Setter
        @jakarta.validation.constraints.Min(1)
        private long attemptWindowSeconds = 60;

        @Getter
        @Setter
        @jakarta.validation.constraints.Min(1)
        private int failedAttemptsBeforeLockout = 5;

        @Getter
        @Setter
        @jakarta.validation.constraints.Min(1)
        private long lockoutSeconds = 300;
    }
}
