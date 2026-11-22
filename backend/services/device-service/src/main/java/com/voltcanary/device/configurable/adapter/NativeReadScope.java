package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.domain.NativeAccessPolicy;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.util.List;
import java.util.UUID;

/** A single fresh SQL-authorized grant, or the existing whole-account owner scope. */
record NativeReadScope(
    String digest,
    UUID datasetId,
    NativeAccessPolicy policy,
    NativeSharedAccess shared,
    NativeWorkspaceIdentity.Membership membership,
    long associationVersion,
    NativeWorkspaceIdentity.ResourceReads resources
) {
    static NativeReadScope owner(String digest) {
        return new NativeReadScope(digest, null, null, null, null, 0, null);
    }

    NativeReadScope withResources(NativeWorkspaceIdentity.ResourceReads reads) {
        if (
            reads.associationVersion() != associationVersion ||
            !reads.membershipDigest().equals(membership.digest())
        ) {
            throw new VclException("ACCESS_SCOPE_CHANGED", "");
        }
        return new NativeReadScope(
            VclCanonical.sha256(digest + ":" + reads.digest()),
            datasetId,
            policy,
            shared,
            membership,
            associationVersion,
            reads
        );
    }

    void target(UUID dataset) {
        if (datasetId != null && !datasetId.equals(dataset)) throw new VclException(
            "DATA_ACCESS_DENIED",
            ""
        );
    }

    JsonNode metadata(NativeDataset dataset) {
        target(dataset.schema().id());
        var definition = (ObjectNode) dataset.metadata().deepCopy();
        if (policy == null) return definition;
        var fields = definition.putArray("fields");
        dataset
            .schema()
            .fields()
            .stream()
            .filter(field ->
                policy.readFields().contains(UUID.fromString(field.path("id").asText()))
            )
            .forEach(fields::add);
        var keys = definition.putArray("keyFieldIds");
        dataset
            .schema()
            .keys()
            .stream()
            .filter(policy.readFields()::contains)
            .forEach(id -> keys.add(id.toString()));
        var indexes = ((ObjectNode) definition.path("storage")).putArray("indexedFieldIds");
        dataset
            .schema()
            .indexes()
            .stream()
            .filter(policy.readFields()::contains)
            .forEach(id -> indexes.add(id.toString()));
        return definition;
    }

    String rowPredicate(List<Object> parameters) {
        if (policy == null || policy.allRows()) return "TRUE";
        if (policy.recordIds().isEmpty()) return "FALSE";
        policy.recordIds().stream().sorted().forEach(parameters::add);
        return (
            "record_id IN (" +
            String.join(",", java.util.Collections.nCopies(policy.recordIds().size(), "?")) +
            ")"
        );
    }
}
