package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.NativeHistoryWorkflows;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeHistoryRows;
import com.voltcanary.device.security.AuthenticatedUserResolver;
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

@RestController
@RequestMapping("/data/workspaces/{workspace}/accounts/{account}/datasets/{dataset}/history")
@RequiredArgsConstructor
public class NativeSharedHistoryController {

    private final AuthenticatedUserResolver users;
    private final NativeHistoryWorkflows history;

    @PostMapping("/revision")
    public NativeHistoryRows.Revision revision(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        return history.revision(
            new NativeSharedAccess(
                account,
                users.resolve(headers).id(),
                workspace,
                dataset,
                grantId
            ),
            body
        );
    }

    @GetMapping("/generations")
    public NativeHistoryRows.Generations generations(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestParam(required = false) UUID afterGeneration,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return history.generations(
            new NativeSharedAccess(
                account,
                users.resolve(headers).id(),
                workspace,
                dataset,
                grantId
            ),
            afterGeneration,
            limit
        );
    }

    @GetMapping("/generations/{generation}/schema")
    public NativeHistoryRows.HistoricalSchema schema(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @PathVariable UUID generation
    ) {
        return history.schema(
            new NativeSharedAccess(
                account,
                users.resolve(headers).id(),
                workspace,
                dataset,
                grantId
            ),
            generation
        );
    }

    @PostMapping("/query")
    public NativeQueryPage query(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID workspace,
        @PathVariable UUID account,
        @PathVariable UUID dataset,
        @RequestParam UUID grantId,
        @RequestBody byte[] body
    ) {
        return history.query(
            new NativeSharedAccess(
                account,
                users.resolve(headers).id(),
                workspace,
                dataset,
                grantId
            ),
            body
        );
    }
}
