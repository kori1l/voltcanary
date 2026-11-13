package com.voltcanary.common.test;

import com.voltcanary.common.security.JwtAccessTokenService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

/** Ephemeral per-JVM test key. Applications must explicitly opt in through test properties. */
public final class SignedUserTokens {
    public static final String KEY_ID = "isolated-test";
    public static final String PUBLIC_KEYS;
    private static final JwtAccessTokenService TOKENS;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            PUBLIC_KEYS = KEY_ID + "=" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            TOKENS = new JwtAccessTokenService(KEY_ID,
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), PUBLIC_KEYS,
                    "voltcanary-identity", "voltcanary-api");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private SignedUserTokens() { }

    public static String create(UUID user, String email, String role) {
        return TOKENS.create(user.toString(), email, role, 900);
    }
}
