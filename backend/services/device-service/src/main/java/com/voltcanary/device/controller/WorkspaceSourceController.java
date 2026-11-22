package com.voltcanary.device.controller;

import com.voltcanary.device.service.WorkspaceSourceReads;
import com.voltcanary.device.service.WorkspaceSourceReads.Source;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceSourceController {

    private final WorkspaceSourceReads useCase;

    public WorkspaceSourceController(WorkspaceSourceReads useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/workspaces/{workspace}/devices/{device}/data-sources")
    public List<Source> list(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("workspace") UUID workspace,
        @PathVariable("device") UUID device,
        @RequestParam("accountId") UUID account
    ) {
        return useCase.list(authorization, workspace, device, account);
    }
}
