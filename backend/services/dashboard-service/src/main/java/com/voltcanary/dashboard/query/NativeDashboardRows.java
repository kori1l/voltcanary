package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.UUID;

/** Dashboard profile for one bounded, owner-authorized VCL table page. */
public final class NativeDashboardRows {

    public static final String TYPE = "configured-records";

    private NativeDashboardRows() {}

    public static UUID validate(JsonNode document) {
        if (
            document == null ||
            !"VCL/1".equals(document.path("language").asText()) ||
            !"QUERY".equals(document.path("kind").asText())
        ) throw invalid();
        var spec = document.path("spec");
        var target = spec.path("target");
        var limit = spec.path("limit");
        var selection = spec.path("select");
        var revision = target.path("schemaRevision");
        if (
            !"LIVE".equals(spec.at("/read/mode").asText()) ||
            spec.hasNonNull("cursor") ||
            !limit.isIntegralNumber() ||
            !limit.canConvertToInt() ||
            limit.intValue() < 1 ||
            limit.intValue() > 25 ||
            !selection.isArray() ||
            selection.isEmpty() ||
            selection.size() > 16 ||
            !revision.isIntegralNumber() ||
            !revision.canConvertToLong() ||
            revision.longValue() < 1
        ) throw invalid();
        try {
            var dataset = UUID.fromString(target.path("datasetId").asText());
            var fields = new java.util.HashSet<UUID>();
            for (var field : selection) {
                if (
                    !field.isTextual() || !fields.add(UUID.fromString(field.textValue()))
                ) throw invalid();
            }
            return dataset;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    public static void project(ObjectNode output, JsonNode ownerPage, JsonNode query) {
        var spec = query.path("spec");
        var manifest = ownerPage.path("readManifest");
        var rows = ownerPage.path("rows");
        if (
            !rows.isArray() ||
            rows.size() > spec.path("limit").intValue() ||
            !manifest.path("datasetId").equals(spec.at("/target/datasetId")) ||
            manifest.path("schemaRevision").asLong(-1) !=
                spec.at("/target/schemaRevision").longValue()
        ) {
            throw new DashboardFailure(
                "INVALID_READ_RESPONSE",
                "Table response does not match the saved widget query"
            );
        }
        var records = output.putObject("configuredRecords");
        records.set("columns", spec.path("select"));
        records.set("rows", rows);
        records.set("completeness", ownerPage.path("completeness"));
        records.put("hasMore", ownerPage.hasNonNull("nextCursor"));
        records.set("readManifest", manifest);
    }

    private static DashboardFailure invalid() {
        return new DashboardFailure(
            "VALIDATION_ERROR",
            "Select up to 16 fields from one table in a bounded live query"
        );
    }
}
