package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.UUID;

final class NativeSharedMutationChecks {
    private NativeSharedMutationChecks() { }

    static void writer(NativeReadScope access) {
        if (access.policy() == null) return;
        require(access.policy().actions().stream().anyMatch(action -> java.util.Set.of("INSERT", "UPDATE", "DELETE").contains(action)));
    }

    static void review(NativeReadScope access, NativeRowReview review) {
        if (access.policy() == null) return;
        access.target(review.datasetId());
        require(access.digest().equals(review.scopeDigest()));
    }

    static void fields(NativeReadScope access, NativeDataset dataset, VclDocument document) {
        if (access == null || access.policy() == null) return;
        access.target(dataset.schema().id());
        access.policy().validate(dataset);
        String operation = document.spec().path("operation").asText();
        require(access.policy().actions().contains(operation));
        for (var pair : document.spec().path("key")) {
            UUID id = UUID.fromString(pair.path("fieldId").asText());
            require(id.equals(dataset.recordIdFieldId()) || access.policy().readFields().contains(id));
            if (operation.equals("INSERT")) require(access.policy().writeFields().contains(id));
            if (id.equals(dataset.recordIdFieldId()) && !access.policy().allRows()) {
                require(access.policy().permits(UUID.fromString(pair.at("/literal/value").asText())));
            }
        }
        for (var pair : document.spec().path("values")) require(access.policy().writeFields().contains(UUID.fromString(pair.path("fieldId").asText())));
    }

    /** Applies after type checking but before row CAS/results can disclose an ungranted identity. */
    static void rows(Connection connection, NativeReadScope access, NativeDataset dataset, VclDocument document) throws SQLException {
        if (access == null || access.policy() == null || access.policy().allRows()) return;
        require(!document.spec().path("operation").asText().equals("INSERT"));
        var values = new LinkedHashMap<UUID, JsonNode>();
        document.spec().path("key").forEach(pair -> values.put(UUID.fromString(pair.path("fieldId").asText()), pair.path("literal")));
        Object key = dataset.schema().keys().isEmpty() ? UUID.fromString(values.get(dataset.recordIdFieldId()).path("value").asText())
                : VclCanonical.sha256(NativeRowCodec.keyTuple(dataset.schema(), values));
        String condition = dataset.schema().keys().isEmpty() ? "record_id=?" : "external_key=?";
        try (var query = connection.prepareStatement("SELECT record_id FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) + " WHERE " + condition)) {
            query.setObject(1, key);
            try (var row = query.executeQuery()) { require(row.next() && access.policy().permits(row.getObject(1, UUID.class))); }
        }
    }

    static void reference(Connection connection, NativeReadScope access, NativeDataset dataset, JsonNode target,
            UUID id, UUID created, String operation) throws SQLException {
        if (access == null || access.policy() == null) return;
        if (target.path("kind").asText().equals("DATASET")) {
            UUID destination = UUID.fromString(target.path("datasetId").asText());
            if (destination.equals(dataset.schema().id())) {
                require(access.policy().permits(id) || operation.equals("INSERT") && access.policy().allRows() && id.equals(created));
                return;
            }
            var grants = new java.util.ArrayList<UUID>();
            try (var query = connection.prepareStatement("SELECT id,subject_kind,subject_id FROM cfg_native_access_grant WHERE account_id=? AND workspace_id=? AND dataset_id=? AND status='ACTIVE' ORDER BY id LIMIT 1001")) {
                query.setObject(1, dataset.accountId());
                query.setObject(2, access.shared().workspace());
                query.setObject(3, destination);
                int count = 0;
                try (var row = query.executeQuery()) {
                    while (row.next()) {
                        require(++count <= 1000);
                        UUID subject = row.getObject(3, UUID.class);
                        if (row.getString(2).equals("USER") && subject.equals(access.shared().actor())
                                || row.getString(2).equals("TEAM") && access.membership().teams().contains(subject)) grants.add(row.getObject(1, UUID.class));
                    }
                }
            }
            for (UUID grant : grants) {
                var context = new NativeSharedAccess(dataset.accountId(), access.shared().actor(), access.shared().workspace(), destination, grant);
                var permitted = NativeWorkspaceGrantScope.load(connection, context, access.membership());
                if (permitted.policy().permits(id)) return;
            }
            throw new VclException("DATA_ACCESS_DENIED", "");
        }
        require(access.resources() != null);
        if (target.path("resourceKind").asText().equals("DEVICE")) {
            require(access.resources().deviceIds().contains(id));
            return;
        }
        require(target.path("resourceKind").asText().equals("SOURCE"));
        try (var query = connection.prepareStatement("SELECT s.device_id FROM data_sources s JOIN devices d ON d.id=s.device_id WHERE d.account_id=? AND s.id=? AND s.status<>'REVOKED' AND d.status<>'DECOMMISSIONED'")) {
            query.setObject(1, dataset.accountId());
            query.setObject(2, id);
            try (var row = query.executeQuery()) { require(row.next() && access.resources().deviceIds().contains(row.getObject(1, UUID.class))); }
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new VclException("DATA_ACCESS_DENIED", "");
    }
}
