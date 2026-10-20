package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.application.DashboardCatalog;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.port.DashboardOwnership;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
public class DashboardImportController {
    private final DashboardCatalog catalog;
    private final DashboardOwnership ownership;
    private final DashboardRequestDecoder decoder;
    public DashboardImportController(DashboardCatalog catalog,DashboardOwnership ownership,DashboardRequestDecoder decoder) {
        this.catalog=catalog;this.ownership=ownership;this.decoder=decoder;
    }
    @PostMapping("/dashboards/import")
    public Dashboard importTemplate(@RequestHeader("Authorization") String authorization,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body) {
        if(!body.path("reviewed").asBoolean() || !body.path("definition").isObject()) throw new DashboardFailure("VALIDATION_ERROR","Review the template before importing");
        ObjectNode definition=((ObjectNode)body.path("definition")).deepCopy();
        definition.putObject("defaultContext");definition.putArray("sections");
        for(JsonNode node:definition.path("widgets")) {
            if(!node.isObject()) throw new DashboardFailure("VALIDATION_ERROR","Invalid template widget");
            ObjectNode widget=(ObjectNode)node;
            widget.putObject("binding").put("mode","UNBOUND");widget.putObject("query");widget.putObject("payload");widget.putObject("navigation");
            widget.put("title",widget.path("type").asText());
        }
        ObjectNode sanitized=definition.objectNode();sanitized.set("definition",definition);
        return catalog.create(ownership.resolve(authorization),authorization,key,body,decoder.definition(sanitized));
    }
}
