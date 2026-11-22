package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;

/** Implemented by an owner-authorized snapshot adapter. Never populate from untrusted HTTP/model JSON. */
public interface VclMetadata {
    enum Resource {
        DATASET,
        BINDING,
        EXECUTION_GRANT,
        EVIDENCE_POLICY,
        CONNECTION,
        ENDPOINT,
        SECRET,
        ADAPTER,
        REFERENCE,
    }

    record Snapshot(UUID id, long revision, JsonNode definition) {
        public Snapshot {
            definition = definition.deepCopy();
        }

        @Override
        public JsonNode definition() {
            return definition.deepCopy();
        }

        @Override
        public String toString() {
            return "Snapshot[id=" + id + ", revision=" + revision + "]";
        }
    }

    String accessScopeDigest();
    Optional<Snapshot> dataset(UUID id, long schemaRevision);
    Optional<Snapshot> binding(UUID id, long schemaRevision);
    boolean permits(Resource kind, UUID id);

    /** Returns an authorized typed identity for a literal REF. Absence is deliberately fail-closed. */
    default Optional<VclType> referenceType(UUID id) {
        return Optional.empty();
    }

    default Optional<String> artifactSha256(UUID id) {
        return Optional.empty();
    }
}
