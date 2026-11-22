package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.configurable.port.GrantScopeAuthority;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclDatasetDescriptions;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Authorizes exact resource/field scopes; capture readiness is deliberately a separate gate. */
@Component
public class HttpGrantScopeAuthority implements GrantScopeAuthority {

    private final ConfigurableOwnerReads reads;

    public HttpGrantScopeAuthority(
        RestClient.Builder builder,
        AlertAuthorizationProperties properties
    ) {
        reads = new ConfigurableOwnerReads(
            builder,
            properties.getDeviceServiceBaseUrl(),
            properties.getTimeout()
        );
    }

    @Override
    public JsonNode validate(
        String authorization,
        UUID actor,
        UUID account,
        AutomationGrantScope scope
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var pins = JsonNodeFactory.instance.arrayNode();
        String ownerScope = null;
        try {
            for (var target : scope.sourceBindings()) {
                var value = VclSourceBindingDescription.parse(
                    reads.sourceBinding(
                        authorization,
                        target.id(),
                        target.schemaRevision(),
                        deadline
                    )
                );
                if (
                    !actor.equals(value.actorId()) ||
                    !account.equals(value.accountId()) ||
                    !target.id().equals(value.id()) ||
                    value.schemaRevision() != target.schemaRevision()
                ) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                if (
                    !value.sourceActive() || value.schemaRevision() != value.currentSchemaRevision()
                ) throw new VclException("GRANT_SCOPE_CHANGED", "");
                if (
                    ownerScope != null && !ownerScope.equals(value.accessScopeDigest())
                ) throw new VclException("GRANT_SCOPE_CHANGED", "");
                ownerScope = value.accessScopeDigest();
                var allowed = new HashSet<UUID>();
                value
                    .definition()
                    .path("fields")
                    .forEach(field -> allowed.add(UUID.fromString(field.path("id").asText())));
                if (!allowed.containsAll(target.fieldIds())) throw new VclException(
                    "GRANT_FIELD_UNAVAILABLE",
                    ""
                );
                pins.addObject()
                    .put("owner", "device-service")
                    .put("kind", "SOURCE_BINDING")
                    .put("resourceId", target.id().toString())
                    .put("resourceRevision", target.schemaRevision())
                    .put("schemaRevision", target.schemaRevision())
                    .put("snapshotId", value.snapshotId().toString())
                    .put("accessScopeDigest", value.accessScopeDigest())
                    .put("definitionDigest", value.definitionDigest())
                    .put("sourceStateDigest", value.sourceStateDigest())
                    .putNull("checkpoint");
            }
            for (var target : scope.datasets()) {
                var value = VclDatasetDescriptions.parse(
                    reads.dataset(authorization, target.id(), target.schemaRevision(), deadline)
                );
                if (
                    !target.id().equals(value.id()) ||
                    target.schemaRevision() != value.schemaRevision() ||
                    !value.accessScopeDigest().equals(ownerScope)
                ) throw new VclException("GRANT_SCOPE_CHANGED", "");
                var allowed = new HashSet<UUID>();
                value
                    .definition()
                    .path("fields")
                    .forEach(field -> allowed.add(UUID.fromString(field.path("id").asText())));
                allowed.add(
                    UUID.fromString(value.definition().at("/systemFields/recordIdFieldId").asText())
                );
                if (!allowed.containsAll(target.fieldIds())) throw new VclException(
                    "GRANT_FIELD_UNAVAILABLE",
                    ""
                );
                pins.addObject()
                    .put("owner", "device-service")
                    .put("kind", "DATASET")
                    .put("resourceId", target.id().toString())
                    .put("resourceRevision", target.schemaRevision())
                    .put("schemaRevision", target.schemaRevision())
                    .put("accessScopeDigest", value.accessScopeDigest())
                    .put("schemaDigest", value.schemaDigest())
                    .put("generation", value.generation().toString())
                    .putNull("snapshotId")
                    .putNull("checkpoint");
            }
            return pins;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (VclException failure) {
            throw failure;
        } catch (IllegalArgumentException | ClassCastException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }
}
