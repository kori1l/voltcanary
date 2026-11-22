package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.application.NativeReviewRequests;
import com.voltcanary.device.configurable.application.NativeRowWorkflows;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data/datasets/{id}")
@RequiredArgsConstructor
public class NativeRowsController {

    private final AuthenticatedUserResolver users;
    private final NativeRowWorkflows rows;
    private final NativeRowQueries queries;
    private final VclParser parser = new VclParser();

    @PostMapping("/mutation-reviews")
    public NativeRowWorkflows.View prepare(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        return rows.prepare(users.resolve(headers), id, NativeReviewRequests.preparation(body));
    }

    @PostMapping("/query")
    public NativeQueryPage query(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        return queries.query(rows.account(actor), actor.id(), queryDocument(id, body));
    }

    @PostMapping("/query-snapshots")
    public NativeRowQueries.Snapshot seal(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        return queries.seal(rows.account(actor), actor.id(), queryDocument(id, body));
    }

    @PostMapping("/query-asof-snapshots")
    public NativeRowQueries.Snapshot sealAsOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var envelope = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "keyFieldIds")
        );
        return queries.seal(
            rows.account(actor),
            actor.id(),
            queryDocument(id, envelope.path("document")),
            asOfProjection(envelope)
        );
    }

    @PostMapping("/query-asof-manifest")
    public NativeQueryPage.ReadManifest inspectAsOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var envelope = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "keyFieldIds")
        );
        return queries.inspectAsOf(
            rows.account(actor),
            actor.id(),
            queryDocument(id, envelope.path("document")),
            asOfProjection(envelope)
        );
    }

    static NativeRowQueries.AsOfProjection asOfProjection(
        com.fasterxml.jackson.databind.JsonNode envelope
    ) {
        var keyIds = envelope.path("keyFieldIds");
        if (!keyIds.isArray() || keyIds.isEmpty() || keyIds.size() > 8) throw new VclException(
            "ASOF_FIELDS_INVALID",
            ""
        );
        var fields = new java.util.ArrayList<UUID>();
        keyIds.forEach(field -> fields.add(asOfField(field)));
        return new NativeRowQueries.AsOfProjection(
            asOfField(envelope.path("intervalFieldId")),
            fields
        );
    }

    @PostMapping("/query-asof")
    public NativeRowQueries.AsOf asOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var envelope = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "eventTime", "keys")
        );
        var document = queryDocument(id, envelope.path("document"));
        return queries.asOf(
            rows.account(actor),
            actor.id(),
            document,
            asOfField(envelope.path("intervalFieldId")),
            asOfTime(envelope.path("eventTime")),
            asOfKeys(envelope)
        );
    }

    @PostMapping("/query-asof-split")
    public NativeRowQueries.AsOfSplit splitAsOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var envelope = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "start", "end", "keys")
        );
        return queries.splitAsOf(
            rows.account(actor),
            actor.id(),
            queryDocument(id, envelope.path("document")),
            asOfField(envelope.path("intervalFieldId")),
            asOfTime(envelope.path("start")),
            asOfTime(envelope.path("end")),
            asOfKeys(envelope)
        );
    }

    static LinkedHashMap<UUID, com.fasterxml.jackson.databind.JsonNode> asOfKeys(
        com.fasterxml.jackson.databind.JsonNode envelope
    ) {
        var keyList = envelope.path("keys");
        if (!keyList.isArray() || keyList.isEmpty() || keyList.size() > 8) throw new VclException(
            "ASOF_FIELDS_INVALID",
            ""
        );
        var keys = new LinkedHashMap<UUID, com.fasterxml.jackson.databind.JsonNode>();
        for (var key : keyList) {
            if (
                !key.isObject() ||
                key.size() != 2 ||
                !key.has("literal") ||
                keys.putIfAbsent(asOfField(key.path("fieldId")), key.path("literal")) != null
            ) {
                throw new VclException("ASOF_FIELDS_INVALID", "");
            }
        }
        return keys;
    }

    Instant asOfTime(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isTextual()) throw new VclException("ASOF_TIME_INVALID", "");
        var time = parser.literal(
            JsonNodeFactory.instance
                .objectNode()
                .put("type", "INSTANT")
                .set("value", value)
                .toString()
        );
        return Instant.parse(time.path("value").textValue());
    }

    static UUID asOfField(com.fasterxml.jackson.databind.JsonNode value) {
        try {
            UUID id = UUID.fromString(value.asText());
            if (
                !id.toString().equalsIgnoreCase(value.asText())
            ) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new VclException("ASOF_FIELDS_INVALID", "");
        }
    }

    private com.voltcanary.vcl.VclDocument queryDocument(UUID id, byte[] body) {
        var envelope = NativeReviewRequests.closed(body, Set.of("document"));
        return queryDocument(id, envelope.path("document"));
    }

    com.voltcanary.vcl.VclDocument queryDocument(
        UUID id,
        com.fasterxml.jackson.databind.JsonNode value
    ) {
        var document = parser.parse(value.toString());
        if (
            !document.spec().at("/target/datasetId").asText().equals(id.toString())
        ) throw new VclException("TARGET_CONFLICT", "");
        return document;
    }
}
