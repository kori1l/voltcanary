package com.voltcanary.device.configurable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog;
import com.voltcanary.device.configurable.domain.NativeSchema;
import java.util.UUID;

final class NativeFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final UUID TEXT = new UUID(0, 1);
    static final UUID AMOUNT = new UUID(0, 2);

    static ObjectNode document(UUID datasetId, boolean history) {
        var root = MAPPER.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
        var spec = root
            .putObject("spec")
            .put("id", datasetId.toString())
            .put("revision", 1)
            .put("label", "private-label-\"; DROP TABLE accounts;--");
        var fields = spec.putArray("fields");
        fields
            .addObject()
            .put("id", TEXT.toString())
            .put("label", "Name")
            .put("type", "TEXT")
            .put("nullable", false)
            .put("maxLength", 120);
        fields
            .addObject()
            .put("id", AMOUNT.toString())
            .put("label", "Amount")
            .put("type", "DECIMAL")
            .put("nullable", true)
            .put("precision", 18)
            .put("scale", 3)
            .put("unit", "V");
        String[] kinds = {
            "BOOLEAN",
            "INT64",
            "DATE",
            "INSTANT",
            "DURATION",
            "POINT",
            "REF",
            "INTERVAL",
            "ENUM",
        };
        for (int i = 0; i < kinds.length; i++) {
            var field = fields
                .addObject()
                .put("id", new UUID(0, i + 3).toString())
                .put("label", "Field " + i)
                .put("type", kinds[i])
                .put("nullable", true);
            if (kinds[i].equals("INT64")) field.put("unit", "1");
            if (kinds[i].equals("REF")) field
                .putObject("target")
                .put("kind", "RESOURCE")
                .put("resourceKind", "DEVICE");
            if (kinds[i].equals("ENUM")) field.putArray("values").add("OPEN").add("CLOSED");
        }
        spec.putArray("keyFieldIds").add(TEXT.toString());
        var storage = spec
            .putObject("storage")
            .put("mode", "NATIVE")
            .put("history", history ? "REVISIONS" : "NONE");
        storage.putArray("indexedFieldIds").add(TEXT.toString()).add(AMOUNT.toString());
        return root;
    }

    static NativeSchema schema(UUID account, UUID id, boolean history) {
        return NativeSchema.compile(
            document(id, history).toString(),
            JdbcNativeDatasetCatalog.emptyMetadata(account)
        );
    }

    private NativeFixtures() {}
}
