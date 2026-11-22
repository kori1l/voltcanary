package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.aiagent.config.AiAgentProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiSecretKeyRotationTest {

    @Test
    void readsOldSecretsByTheirRecordedVersionAndWritesOnlyWithCurrentKey() {
        var oldProperties = new AiAgentProperties();
        oldProperties.getSecrets().setEncryptionKey("old-test-key");
        oldProperties.getSecrets().setKeyVersion("v1");
        var old = new AiSecretEncryptionService(oldProperties).encrypt("provider-secret");
        var currentProperties = new AiAgentProperties();
        currentProperties.getSecrets().setEncryptionKey("new-test-key");
        currentProperties.getSecrets().setKeyVersion("v2");
        currentProperties.getSecrets().setPreviousKeys(Map.of("v1", "old-test-key"));
        var current = new AiSecretEncryptionService(currentProperties);
        assertThat(current.decrypt(old.encryptedValue(), old.keyVersion())).isEqualTo(
            "provider-secret"
        );
        var fresh = current.encrypt("fresh-secret");
        assertThat(fresh.keyVersion()).isEqualTo("v2");
        assertThat(current.decrypt(fresh.encryptedValue(), fresh.keyVersion())).isEqualTo(
            "fresh-secret"
        );
        assertThatThrownBy(() -> current.decrypt(old.encryptedValue(), "missing")).isInstanceOf(
            IllegalStateException.class
        );
    }
}
