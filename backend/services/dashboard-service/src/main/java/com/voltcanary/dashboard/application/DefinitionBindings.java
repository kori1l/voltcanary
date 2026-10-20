package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DefinitionBindings {
    private final DashboardOwnership ownership;
    private final ObjectMapper mapper;
    private final com.voltcanary.dashboard.port.DashboardEvidenceReads reads;

    public DefinitionBindings(DashboardOwnership ownership, ObjectMapper mapper,com.voltcanary.dashboard.port.DashboardEvidenceReads reads) {
        this.ownership = ownership;
        this.mapper = mapper;
        this.reads=reads;
    }

    public DashboardDefinition template(DashboardDefinition definition) {
        ObjectNode tree=mapper.valueToTree(definition);
        tree.putObject("defaultContext");tree.putArray("sections");
        for(var node:tree.path("widgets")) {
            ObjectNode widget=(ObjectNode)node;
            widget.putObject("binding").put("mode","UNBOUND");widget.putObject("query");widget.putObject("navigation");widget.putObject("payload");
            widget.put("title",widget.path("type").asText());
        }
        return mapper.convertValue(tree,DashboardDefinition.class);
    }

    public void requireAuthorized(String authorization, DashboardActor actor, DashboardDefinition definition) {
        if (definition.defaultContext().path("scope").hasNonNull("workspaceId")) {
            var decoder=new com.voltcanary.dashboard.query.WidgetQueryDecoder(mapper);
            if (reads==null) throw new DashboardFailure("NO_ACCESS","Workspace resource authority unavailable");
            for (var widget:definition.widgets()) {
                var binding=decoder.scope(widget.binding());
                if (binding.workspaceId()==null) throw new DashboardFailure("NO_ACCESS","Explicit collaboration binding required");
                var snapshot=reads.scope(authorization,actor,binding);
                var ids=snapshot.devices().stream().map(com.voltcanary.dashboard.port.DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet());
                if (!ids.containsAll(binding.deviceIds())) throw new DashboardFailure("NO_ACCESS","Rebind inaccessible equipment before publication");
            }
            return;
        }
        if (!unavailable(authorization, actor, mapper.valueToTree(definition)).isEmpty()) {
            throw new DashboardFailure("VALIDATION_ERROR", "Definition contains inaccessible resource bindings", "binding");
        }
    }

    public DashboardDefinition safeCopy(String authorization, DashboardActor actor, DashboardDefinition definition) {
        ObjectNode tree = mapper.valueToTree(definition);
        stripCollaboration(tree);
        Set<String> denied = unavailable(authorization, actor, tree);
        if (!denied.isEmpty()) {
            clearDenied(tree.path("defaultContext"), denied);
            for (JsonNode widget : tree.path("widgets")) {
                if (containsDenied(widget, denied)) {
                    ObjectNode object = (ObjectNode) widget;
                    object.set("binding", mapper.createObjectNode().put("mode", "UNBOUND"));
                    object.set("query", mapper.createObjectNode());
                    object.set("navigation", mapper.createObjectNode());
                    clearDenied(object.path("payload"), denied);
                }
            }
            clearDenied(tree.path("sections"), denied);
        }
        return mapper.convertValue(tree, DashboardDefinition.class);
    }
    private void stripCollaboration(JsonNode tree) {
        if (tree.isObject()) {
            ((ObjectNode)tree).remove(java.util.List.of("workspaceId","accountId","authorizationFingerprint"));
        }
        tree.forEach(this::stripCollaboration);
    }

    private Set<String> unavailable(String authorization, DashboardActor actor, JsonNode tree) {
        Set<String> deviceIds = new HashSet<>();
        Set<String> locationIds = new HashSet<>();
        collect(tree, deviceIds, locationIds);
        if (deviceIds.size() > 500 || locationIds.size() > 500) {
            throw new DashboardFailure("VALIDATION_ERROR", "Definition exceeds 500 resource bindings", "binding");
        }
        Set<String> denied = new HashSet<>();
        deviceIds.forEach(id -> {
            if (!ownership.canReadDevice(authorization, UUID.fromString(id), actor)) {
                denied.add(id);
            }
        });
        locationIds.forEach(id -> {
            if (!ownership.canReadLocation(authorization, UUID.fromString(id), actor)) {
                denied.add(id);
            }
        });
        return denied;
    }

    private void collect(JsonNode node, Set<String> devices, Set<String> locations) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String normalized = entry.getKey().toLowerCase().replaceAll("[^a-z0-9]", "");
                Set<String> target = normalized.equals("deviceid") || normalized.equals("deviceids") ? devices
                        : normalized.equals("locationid") || normalized.equals("locationids") ? locations : null;
                if (target != null && !entry.getValue().isNull()) {
                    if (entry.getValue().isArray()) {
                        entry.getValue().forEach(value -> target.add(value.asText()));
                    } else {
                        target.add(entry.getValue().asText());
                    }
                }
                if ((normalized.equals("url") || normalized.equals("href"))
                        && entry.getValue().isTextual() && entry.getValue().asText().startsWith("/devices/")) {
                    String id = entry.getValue().asText().substring("/devices/".length());
                    try {
                        devices.add(UUID.fromString(id).toString());
                    } catch (IllegalArgumentException exception) {
                        throw new DashboardFailure("VALIDATION_ERROR", "Device navigation requires a canonical UUID", "navigation");
                    }
                }
                collect(entry.getValue(), devices, locations);
            });
        } else if (node.isArray()) {
            node.forEach(value -> collect(value, devices, locations));
        }
    }

    private boolean containsDenied(JsonNode node, Set<String> denied) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return denied.contains(node.asText()) || denied.stream().anyMatch(id -> node.asText().endsWith("/" + id));
        }
        for (JsonNode child : node) {
            if (containsDenied(child, denied)) {
                return true;
            }
        }
        return false;
    }

    private void clearDenied(JsonNode node, Set<String> denied) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            var fields = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode value = object.get(field);
                if (containsDenied(value, denied)) {
                    object.remove(field);
                    object.put("bindingState", "UNBOUND");
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> clearDenied(child, denied));
        }
    }
}
