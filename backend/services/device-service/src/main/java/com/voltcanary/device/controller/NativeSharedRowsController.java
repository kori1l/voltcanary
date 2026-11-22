package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.application.NativeReviewRequests;
import com.voltcanary.device.configurable.application.NativeRowWorkflows;
import com.voltcanary.device.configurable.application.NativeSharedRowWorkflows;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.device.configurable.port.NativeSharedRows;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit workspace/account/grant context; the authenticated actor is never replaced with an owner. */
@RestController
@RequestMapping("/data/workspaces/{workspace}")
@RequiredArgsConstructor
public class NativeSharedRowsController {

    private static final String TABLE = "/accounts/{account}/datasets/{dataset}";
    private final AuthenticatedUserResolver users;
    private final NativeSharedRows shared;
    private final NativeRowsController requests;
    private final NativeSharedRowWorkflows changes;

    @GetMapping("/datasets")
    public NativeSharedRows.SharedTables discover(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @RequestParam(required = false) UUID afterGrant,
        @RequestParam(defaultValue = "25") int limit
    ) {
        return shared.discover(workspace, users.resolve(headers).id(), afterGrant, limit);
    }

    @PostMapping(TABLE + "/mutation-reservations")
    public NativeRowWorkflows.View reserve(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        return changes.reserve(access(headers, workspace, account, dataset, grantId), body);
    }

    @PostMapping(TABLE + "/mutation-reviews")
    public NativeRowWorkflows.View prepare(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        return changes.prepare(access(headers, workspace, account, dataset, grantId), body);
    }

    @GetMapping(TABLE + "/reviews/{review}")
    public NativeRowWorkflows.View inspect(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @PathVariable UUID review,
        @RequestParam UUID grantId
    ) {
        return changes.inspect(access(headers, workspace, account, dataset, grantId), review);
    }

    @PostMapping(TABLE + "/reviews/{review}/apply")
    public NativeRowWorkflows.View apply(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @PathVariable UUID review,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        return changes.apply(access(headers, workspace, account, dataset, grantId), review, body);
    }

    @PostMapping(TABLE + "/reviews/{review}/reconcile")
    public NativeRowWorkflows.View reconcile(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @PathVariable UUID review,
        @RequestParam UUID grantId,
        @RequestBody(required = false) byte[] body
    ) {
        NativeReviewRequests.reconciliation(body);
        return changes.inspect(access(headers, workspace, account, dataset, grantId), review);
    }

    @GetMapping(TABLE)
    public JsonNode describe(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId
    ) {
        return shared.describe(access(headers, workspace, account, dataset, grantId));
    }

    @PostMapping(TABLE + "/query")
    public NativeQueryPage query(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        var request = NativeReviewRequests.closed(body, Set.of("document"));
        return shared.query(
            access(headers, workspace, account, dataset, grantId),
            requests.queryDocument(dataset, request.path("document"))
        );
    }

    @PostMapping(TABLE + "/query-snapshots")
    public NativeRowQueries.Snapshot seal(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        var request = NativeReviewRequests.closed(body, Set.of("document"));
        return shared.seal(
            access(headers, workspace, account, dataset, grantId),
            requests.queryDocument(dataset, request.path("document")),
            null
        );
    }

    @PostMapping(TABLE + "/query-asof-snapshots")
    public NativeRowQueries.Snapshot sealAsOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "keyFieldIds")
        );
        return shared.seal(
            access(headers, workspace, account, dataset, grantId),
            requests.queryDocument(dataset, request.path("document")),
            NativeRowsController.asOfProjection(request)
        );
    }

    @PostMapping(TABLE + "/query-asof-manifest")
    public NativeQueryPage.ReadManifest inspectAsOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "keyFieldIds")
        );
        return shared.inspectAsOf(
            access(headers, workspace, account, dataset, grantId),
            requests.queryDocument(dataset, request.path("document")),
            NativeRowsController.asOfProjection(request)
        );
    }

    @PostMapping(TABLE + "/query-asof")
    public NativeRowQueries.AsOf asOf(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "eventTime", "keys")
        );
        return shared.asOf(
            access(headers, workspace, account, dataset, grantId),
            requests.queryDocument(dataset, request.path("document")),
            NativeRowsController.asOfField(request.path("intervalFieldId")),
            requests.asOfTime(request.path("eventTime")),
            NativeRowsController.asOfKeys(request)
        );
    }

    @PostMapping(TABLE + "/query-asof-split")
    public NativeRowQueries.AsOfSplit split(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("document", "intervalFieldId", "start", "end", "keys")
        );
        return shared.splitAsOf(
            access(headers, workspace, account, dataset, grantId),
            requests.queryDocument(dataset, request.path("document")),
            NativeRowsController.asOfField(request.path("intervalFieldId")),
            requests.asOfTime(request.path("start")),
            requests.asOfTime(request.path("end")),
            NativeRowsController.asOfKeys(request)
        );
    }

    private NativeSharedAccess access(
        HttpHeaders headers,
        UUID workspace,
        UUID account,
        UUID dataset,
        UUID grant
    ) {
        return new NativeSharedAccess(
            account,
            users.resolve(headers).id(),
            workspace,
            dataset,
            grant
        );
    }
}
