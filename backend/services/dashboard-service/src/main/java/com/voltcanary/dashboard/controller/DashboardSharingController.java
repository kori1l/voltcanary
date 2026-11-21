package com.voltcanary.dashboard.controller;

import com.voltcanary.dashboard.sharing.DashboardSharing;
import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.port.DashboardStorage;
import com.voltcanary.dashboard.sharing.DashboardAudienceRepository.Audience;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardSharingController {
    public record Attach(UUID workspaceId,boolean reviewed) {
    }
    public record GrantCommand(List<Audience> audience,long expectedVersion,boolean reviewed) {
    }
    public record Preview(List<UUID> userIds) {
    }
    public record Transfer(UUID userId,boolean reviewed) {
    }
    private final DashboardSharing sharing;
    private final DashboardOwnership ownership;
    private final DashboardStorage storage;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    @GetMapping("/dashboards/team")
    public List<Dashboard> list(@RequestHeader("Authorization") String authorization) {
        return sharing.list(ownership.resolveReadOnly(authorization));
    }
    @PostMapping("/dashboards/{id}/workspace")
    public void attach(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id,@RequestBody Attach request) {
        sharing.attach(ownership.resolveReadOnly(authorization),id,request.workspaceId(),request.reviewed());
    }
    @GetMapping("/dashboards/{id}/grants")
    public JsonNode grants(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id) {
        var actor=ownership.resolveReadOnly(authorization);
        var result=sharing.metadata(actor,id);
        result.set("audience",mapper.valueToTree(sharing.grants(actor,id)));
        return result;
    }
    @PutMapping("/dashboards/{id}/grants")
    public void grants(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id,@RequestBody GrantCommand request) {
        sharing.grants(ownership.resolveReadOnly(authorization),id,request.audience(),request.expectedVersion(),request.reviewed());
    }
    @PostMapping("/dashboards/{id}/sharing-preview")
    public JsonNode preview(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id,@RequestBody Preview request) {
        return sharing.preview(ownership.resolveReadOnly(authorization),id,request.userIds());
    }
    @PostMapping("/dashboards/{id}/transfer")
    public void transfer(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id,@RequestBody Transfer request) {
        sharing.transfer(ownership.resolveReadOnly(authorization),id,request.userId(),request.reviewed());
    }
    @GetMapping("/dashboards/{id}/sharing-audit")
    public List<Map<String,Object>> audit(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id) {
        return sharing.audit(ownership.resolveReadOnly(authorization),id);
    }
    @GetMapping("/dashboards/{id}/export")
    public DashboardDefinition export(@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id) {
        var dashboard=sharing.require(ownership.resolveReadOnly(authorization),id,"EXPORT");
        var definition=storage.revision(id,dashboard.publishedRevision()).orElseThrow().definition();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(definition);
        tree.putObject("defaultContext");
        for (var node:tree.path("widgets")) {
            var widget=(com.fasterxml.jackson.databind.node.ObjectNode)node;
            widget.putObject("binding").put("mode","UNBOUND");
            widget.putObject("query");
            widget.putObject("payload");
            widget.putObject("navigation");
            widget.put("title",widget.path("type").asText());
        }
        tree.put("name","Dashboard template").put("description","");
        tree.putArray("sections");
        return mapper.convertValue(tree,DashboardDefinition.class);
    }
}
