package com.voltcanary.alert.controller;

import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.service.AlertService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WorkspaceIncidentActionsController {

    public record Resolution(String reason) {}

    private final AlertService alerts;

    @PostMapping("/alerts/workspaces/{workspaceId}/{id}/acknowledge")
    public AlertResponse acknowledge(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader("X-User-Id") String userId,
        @PathVariable("workspaceId") UUID workspaceId,
        @PathVariable("id") UUID id
    ) {
        return alerts.workspaceAcknowledge(id, authorization, workspaceId, userId, null);
    }

    @PostMapping("/alerts/workspaces/{workspaceId}/{id}/resolve")
    public AlertResponse resolve(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader("X-User-Id") String userId,
        @PathVariable("workspaceId") UUID workspaceId,
        @PathVariable("id") UUID id,
        @RequestBody Resolution request
    ) {
        return alerts.workspaceResolve(
            id,
            request.reason(),
            authorization,
            workspaceId,
            userId,
            null
        );
    }
}
