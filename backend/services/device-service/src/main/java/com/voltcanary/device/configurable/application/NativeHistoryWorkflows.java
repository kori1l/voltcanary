package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeHistoryLeases;
import com.voltcanary.device.configurable.port.NativeHistoryRows;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NativeHistoryWorkflows {

    private record Point(UUID generation, UUID record, long version, List<UUID> select) {}

    private record Query(UUID generation, long through, VclDocument document) {}

    private static final VclParser PARSER = new VclParser();
    private final NativeHistoryRows history;
    private final NativeRowWorkflows rows;
    private final NativeHistoryLeases leases;

    public NativeHistoryLeases.Lease acquire(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("generation", "throughDataVersion", "durationSeconds", "idempotencyKey")
        );
        var through = request.path("throughDataVersion");
        var duration = request.path("durationSeconds");
        require(
            through.isIntegralNumber() &&
                through.canConvertToLong() &&
                duration.isIntegralNumber() &&
                duration.canConvertToInt()
        );
        return leases.acquire(
            rows.account(actor),
            actor.id(),
            dataset,
            uuid(request.path("generation")),
            through.longValue(),
            duration.intValue(),
            NativeReviewRequests.key(request.path("idempotencyKey"))
        );
    }

    public NativeHistoryLeases.Lease release(AuthenticatedUser actor, UUID dataset, UUID lease) {
        return leases.release(rows.account(actor), actor.id(), dataset, lease);
    }

    public NativeHistoryRows.Generations generations(
        AuthenticatedUser actor,
        UUID dataset,
        UUID afterGeneration,
        int limit
    ) {
        return history.generations(
            rows.account(actor),
            actor.id(),
            dataset,
            afterGeneration,
            limit
        );
    }

    public NativeHistoryRows.Generations generations(
        NativeSharedAccess access,
        UUID afterGeneration,
        int limit
    ) {
        return history.generations(access, afterGeneration, limit);
    }

    public NativeHistoryRows.HistoricalSchema schema(NativeSharedAccess access, UUID generation) {
        return history.schema(access, generation);
    }

    public NativeHistoryRows.HistoricalSchema schema(
        AuthenticatedUser actor,
        UUID dataset,
        UUID generation
    ) {
        return history.schema(rows.account(actor), actor.id(), dataset, generation);
    }

    public NativeHistoryRows.Revision revision(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = point(body);
        return history.revision(
            rows.account(actor),
            actor.id(),
            dataset,
            request.generation(),
            request.record(),
            request.version(),
            request.select()
        );
    }

    public NativeHistoryRows.Revision revision(NativeSharedAccess access, byte[] body) {
        var request = point(body);
        return history.revision(
            access,
            request.generation(),
            request.record(),
            request.version(),
            request.select()
        );
    }

    public NativeQueryPage query(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = query(dataset, body);
        return history.query(
            rows.account(actor),
            actor.id(),
            request.generation(),
            request.through(),
            request.document()
        );
    }

    public NativeQueryPage query(NativeSharedAccess access, byte[] body) {
        var request = query(access.dataset(), body);
        return history.query(access, request.generation(), request.through(), request.document());
    }

    private static Point point(byte[] body) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("generation", "recordId", "recordVersion", "select")
        );
        var fields = request.path("select");
        require(fields.isArray() && !fields.isEmpty() && fields.size() <= 64);
        var selected = new ArrayList<UUID>();
        fields.forEach(field -> selected.add(uuid(field)));
        require(selected.stream().distinct().count() == selected.size());
        var version = request.path("recordVersion");
        require(version.isTextual() && version.textValue().matches("[1-9][0-9]{0,18}"));
        long parsed;
        try {
            parsed = Long.parseLong(version.textValue());
        } catch (NumberFormatException invalid) {
            throw new VclException("HISTORY_REQUEST_INVALID", "");
        }
        return new Point(
            uuid(request.path("generation")),
            uuid(request.path("recordId")),
            parsed,
            List.copyOf(selected)
        );
    }

    private static Query query(UUID dataset, byte[] body) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("generation", "throughDataVersion", "document")
        );
        var through = request.path("throughDataVersion");
        require(
            through.isIntegralNumber() &&
                through.canConvertToLong() &&
                through.longValue() >= 1 &&
                through.longValue() <= 9_007_199_254_740_991L
        );
        var document = PARSER.parse(request.path("document").toString());
        require(
            document.kind() == VclDocument.Kind.QUERY &&
                document.spec().at("/target/datasetId").asText().equals(dataset.toString())
        );
        return new Query(uuid(request.path("generation")), through.longValue(), document);
    }

    private static UUID uuid(JsonNode value) {
        require(value.isTextual());
        try {
            UUID id = UUID.fromString(value.textValue());
            require(id.toString().equalsIgnoreCase(value.textValue()));
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new VclException("HISTORY_REQUEST_INVALID", "");
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new VclException("HISTORY_REQUEST_INVALID", "");
    }
}
