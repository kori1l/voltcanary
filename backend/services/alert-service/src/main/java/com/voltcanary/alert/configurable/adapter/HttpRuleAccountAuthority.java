package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.AlertInternalProperties;
import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Mandatory bounded owner check, independent of the legacy authorization toggle. */
@Component
public class HttpRuleAccountAuthority implements RuleAccountAuthority {
    private final ConfigurableOwnerReads reads;
    private final String token;
    private final VclParser parser = new VclParser();

    public HttpRuleAccountAuthority(RestClient.Builder builder, AlertAuthorizationProperties properties, AlertInternalProperties internal) {
        reads = new ConfigurableOwnerReads(builder, properties.getDeviceServiceBaseUrl(), properties.getTimeout());
        token = internal.getServiceToken();
    }

    @Override
    public UUID account(UUID actor) {
        try {
            var value = parser.readJson(reads.accountLifecycle(token, actor, System.nanoTime() + Duration.ofSeconds(5).toNanos()));
            var fields = new java.util.HashSet<String>();
            value.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("userId", "accountId", "status")) || !actor.toString().equals(value.path("userId").asText())
                    || !value.path("userId").isTextual() || !value.path("accountId").isTextual()
                    || !value.path("status").isTextual() || !Set.of("ACTIVE", "DISABLED", "DELETED").contains(value.path("status").asText())) {
                throw new VclException("ACCOUNT_OWNER_UNAVAILABLE", "");
            }
            if (!value.path("status").asText().equals("ACTIVE")) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
            return UUID.fromString(value.path("accountId").asText());
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code().equals("ACCOUNT_UNAVAILABLE") ? "ACCOUNT_ACCESS_REVOKED" : "ACCOUNT_OWNER_UNAVAILABLE", "");
        } catch (VclException failure) {
            if (failure.code().equals("ACCOUNT_ACCESS_REVOKED")) throw failure;
            throw new VclException("ACCOUNT_OWNER_UNAVAILABLE", "");
        } catch (IllegalArgumentException failure) { throw new VclException("ACCOUNT_OWNER_UNAVAILABLE", ""); }
    }
}
