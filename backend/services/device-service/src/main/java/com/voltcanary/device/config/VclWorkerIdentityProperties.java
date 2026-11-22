package com.voltcanary.device.config;

import com.voltcanary.common.security.VclWorkerAssertion;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class VclWorkerIdentityProperties {

    private final String encodedKey;
    private final String keyId;

    public VclWorkerIdentityProperties(
        @Value("${APP_VCL_WORKER_HMAC_KEY:}") String encodedKey,
        @Value("${APP_VCL_WORKER_KEY_ID:worker-1}") String keyId
    ) {
        this.encodedKey = encodedKey;
        this.keyId = keyId;
    }

    public VclWorkerAssertion forAudience(String audience) {
        return encodedKey == null || encodedKey.isBlank()
            ? null
            : new VclWorkerAssertion(
                  Base64.getDecoder().decode(encodedKey),
                  keyId,
                  audience,
                  Clock.systemUTC()
              );
    }
}
