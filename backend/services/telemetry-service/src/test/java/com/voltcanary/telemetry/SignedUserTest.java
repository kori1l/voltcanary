package com.voltcanary.telemetry;

import com.voltcanary.common.test.SignedUserTokens;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

abstract class SignedUserTest {

    @DynamicPropertySource
    static void userKeys(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.key-id", () -> SignedUserTokens.KEY_ID);
        registry.add("app.jwt.public-keys", () -> SignedUserTokens.PUBLIC_KEYS);
    }
}
