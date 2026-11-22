package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.NativeHistoryWorkflows;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.port.NativeHistoryLeases;
import com.voltcanary.device.configurable.port.NativeHistoryRows;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data/datasets/{dataset}/history")
@RequiredArgsConstructor
public class NativeHistoryController {

    private final AuthenticatedUserResolver users;
    private final NativeHistoryWorkflows history;

    @PostMapping("/leases")
    public NativeHistoryLeases.Lease acquire(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestBody byte[] body
    ) {
        return history.acquire(users.resolve(headers), dataset, body);
    }

    @DeleteMapping("/leases/{lease}")
    public NativeHistoryLeases.Lease release(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID lease
    ) {
        return history.release(users.resolve(headers), dataset, lease);
    }

    @GetMapping("/generations")
    public NativeHistoryRows.Generations generations(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestParam(required = false) UUID afterGeneration,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return history.generations(users.resolve(headers), dataset, afterGeneration, limit);
    }

    @PostMapping("/revision")
    public NativeHistoryRows.Revision revision(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestBody byte[] body
    ) {
        return history.revision(users.resolve(headers), dataset, body);
    }

    @GetMapping("/generations/{generation}/schema")
    public NativeHistoryRows.HistoricalSchema schema(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID generation
    ) {
        return history.schema(users.resolve(headers), dataset, generation);
    }

    @PostMapping("/query")
    public NativeQueryPage query(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestBody byte[] body
    ) {
        return history.query(users.resolve(headers), dataset, body);
    }
}
