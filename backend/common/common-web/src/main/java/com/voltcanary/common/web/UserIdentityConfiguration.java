package com.voltcanary.common.web;

import com.voltcanary.common.security.BearerUserIdentity;
import com.voltcanary.common.security.JwtAccessTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserJwtProperties.class)
public class UserIdentityConfiguration {

    @Bean
    public InternalTelemetryFilter internalTelemetryFilter(
        @Value(
            "${app.internal.service-token:${INTERNAL_SERVICE_TOKEN:dev-internal-token}}"
        ) String token
    ) {
        return new InternalTelemetryFilter(token);
    }

    @Bean
    public UserIdentityFilter userIdentityFilter(UserJwtProperties properties) {
        return new UserIdentityFilter(
            new BearerUserIdentity(
                new JwtAccessTokenService(
                    properties.keyId(),
                    properties.publicKeys(),
                    properties.issuer(),
                    properties.audience()
                )
            )
        );
    }
}
