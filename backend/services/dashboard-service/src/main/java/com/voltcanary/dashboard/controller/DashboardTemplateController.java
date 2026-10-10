package com.voltcanary.dashboard.controller;

import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.templates.DashboardTaskTemplates;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardTemplateController {
    private final DashboardOwnership ownership;
    private final DashboardTaskTemplates templates;
    public DashboardTemplateController(DashboardOwnership ownership, DashboardTaskTemplates templates) {
        this.ownership = ownership; this.templates = templates;
    }
    @GetMapping("/dashboard-templates")
    public List<DashboardTaskTemplates.Template> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        ownership.resolve(authorization);
        return templates.catalog();
    }
}
