package com.voltcanary.dashboard.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.port.DashboardStorage;
import com.voltcanary.dashboard.sharing.DashboardAudienceRepository.Audience;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardSharing {
    public static final Set<String> CAPABILITIES=Set.of("VIEW","EDIT","PUBLISH","MANAGE_SHARING","ARCHIVE","COPY","HISTORY","EXPORT");
    private final DashboardAudienceRepository audience;
    private final DashboardWorkspaceAuthority authority;
    private final DashboardStorage storage;
    private final ObjectMapper mapper;

    public List<String> permissions(DashboardActor actor,Dashboard dashboard) {
        var collaboration=audience.collaboration(dashboard.id());
        if (collaboration==null) return actor.userId().equals(dashboard.ownerUserId()) && actor.accountId().equals(dashboard.accountId())?CAPABILITIES.stream().sorted().toList():List.of();
        var member=authority.member(collaboration.workspaceId(),actor.userId());
        if (actor.userId().equals(dashboard.ownerUserId())) return CAPABILITIES.stream().sorted().toList();
        return audience.audience(dashboard.id()).stream().filter(entry->"USER".equals(entry.subjectKind())?entry.subjectId().equals(actor.userId()):member.teamIds().contains(entry.subjectId()))
                .flatMap(entry->entry.capabilities().stream()).distinct().sorted().toList();
    }
    public Dashboard require(DashboardActor actor,UUID id,String capability) {
        Dashboard dashboard=storage.findAny(id).orElseThrow(()->new DashboardFailure("NOT_FOUND","Dashboard unavailable"));
        if (!permissions(actor,dashboard).contains(capability)) throw new DashboardFailure("NO_ACCESS","Dashboard capability unavailable");
        return dashboard;
    }
    public boolean shared(UUID id) {
        return audience.collaboration(id)!=null;
    }
    @Transactional
    public void attach(DashboardActor actor,UUID id,UUID workspace,boolean reviewed) {
        audience.lock(id);
        Dashboard dashboard=storage.findOwned(actor,id).orElseThrow(()->new DashboardFailure("NOT_FOUND","Dashboard unavailable"));
        authority.member(workspace,actor.userId());
        if (!reviewed || shared(id)) throw new DashboardFailure("CONFLICT","Review an unshared dashboard before workspace association");
        audience.attach(id,workspace);
        audience.audit(id,actor.userId(),"WORKSPACE_ASSOCIATED",workspace);
    }
    public List<Audience> grants(DashboardActor actor,UUID id) {
        require(actor,id,"MANAGE_SHARING");
        return audience.audience(id);
    }
    @Transactional
    public void grants(DashboardActor actor,UUID id,List<Audience> recipients,long expectedVersion,boolean reviewed) {
        audience.lock(id);
        require(actor,id,"MANAGE_SHARING");
        var collaboration=audience.collaboration(id);
        if (collaboration==null || !reviewed || recipients==null || recipients.size()>100 || collaboration.version()!=expectedVersion) throw new DashboardFailure("CONFLICT","Audience changed; reload and review");
        Set<String> identities=new java.util.HashSet<>();
        for (Audience entry:recipients) {
            if (entry==null || entry.subjectId()==null || !List.of("USER","TEAM").contains(entry.subjectKind()) || entry.capabilities()==null
                    || entry.capabilities().isEmpty() || !CAPABILITIES.containsAll(entry.capabilities())
                    || !entry.capabilities().contains("VIEW") || entry.capabilities().stream().distinct().count()!=entry.capabilities().size()
                    || !identities.add(entry.subjectKind()+entry.subjectId())) throw new DashboardFailure("VALIDATION_ERROR","Review an explicit recipient grant matrix");
            if ("USER".equals(entry.subjectKind())) authority.member(collaboration.workspaceId(),entry.subjectId());
            else if (!authority.team(collaboration.workspaceId(),entry.subjectId())) throw new DashboardFailure("VALIDATION_ERROR","Recipient team unavailable in this workspace");
        }
        audience.audience(id,recipients);
        audience.audit(id,actor.userId(),"AUDIENCE_REPLACED",null);
    }
    public ObjectNode metadata(DashboardActor actor,UUID id) {
        require(actor,id,"MANAGE_SHARING");
        var collaboration=audience.collaboration(id);
        if (collaboration==null) throw new DashboardFailure("NOT_FOUND","Collaboration unavailable");
        return mapper.valueToTree(collaboration);
    }
    public List<Dashboard> list(DashboardActor actor) {
        return audience.candidates(actor.userId()).stream().map(storage::findAny).flatMap(java.util.Optional::stream).filter(dashboard->{
            try {return permissions(actor,dashboard).contains("VIEW");}
            catch (DashboardFailure denied) {if (!"NO_ACCESS".equals(denied.code())) throw denied;return false;}
        }).toList();
    }
    public ObjectNode preview(DashboardActor actor,UUID id,List<UUID> recipients) {
        Dashboard dashboard=require(actor,id,"MANAGE_SHARING");
        var collaboration=audience.collaboration(id);
        if (collaboration==null || recipients==null || recipients.size()>50) throw new DashboardFailure("VALIDATION_ERROR","Choose active workspace recipients");
        var definition=dashboard.publishedRevision()>0?storage.revision(id,dashboard.publishedRevision()).orElseThrow().definition():storage.draft(id,actor.userId()).definition();
        var result=mapper.createObjectNode();
        var output=result.putArray("recipients");
        for (UUID recipient:recipients) {
            authority.member(collaboration.workspaceId(),recipient);
            var widgets=output.addObject().put("userId",recipient.toString()).putArray("widgets");
            for (var widget:definition.widgets()) {
                String action=action(widget.type());
                boolean usable;
                try {
                    var allowed=authority.resources(collaboration.workspaceId(),dashboard.accountId(),recipient,action).deviceIds();
                    usable=!allowed.isEmpty();
                    if (widget.binding()!=null && widget.binding().path("deviceIds").isArray()) {
                        usable=false;
                        for (var device:widget.binding().path("deviceIds")) if (allowed.contains(UUID.fromString(device.asText()))) usable=true;
                    }
                } catch (DashboardFailure denied) {
                    if (!"NO_ACCESS".equals(denied.code())) throw denied;
                    usable=false;
                }
                widgets.addObject().put("widgetId",widget.id()).put("usable",usable).put("resourceAccessGranted",false);
            }
        }
        return result;
    }
    @Transactional
    public void transfer(DashboardActor actor,UUID id,UUID newOwner,boolean reviewed) {
        audience.lock(id);
        var collaboration=audience.collaboration(id);
        if (collaboration==null || !reviewed) throw new DashboardFailure("VALIDATION_ERROR","Reviewed workspace transfer required");
        var manager=authority.member(collaboration.workspaceId(),actor.userId());
        if (!List.of("OWNER","MANAGER").contains(manager.role())) throw new DashboardFailure("NO_ACCESS","Workspace manager required for ownership transfer");
        authority.member(collaboration.workspaceId(),newOwner);
        var old=storage.findAny(id).orElseThrow().ownerUserId();
        audience.transfer(id,newOwner);
        audience.audience(id,audience.audience(id).stream().filter(entry->!"USER".equals(entry.subjectKind()) || !entry.subjectId().equals(old)).toList());
        audience.audit(id,actor.userId(),"OWNER_TRANSFERRED",newOwner);
    }
    public List<Map<String,Object>> audit(DashboardActor actor,UUID id) {
        require(actor,id,"MANAGE_SHARING");
        return audience.audit(id);
    }
    public DashboardDefinition context(UUID id,DashboardDefinition definition) {
        var collaboration=audience.collaboration(id);
        if (collaboration==null) return definition;
        Dashboard dashboard=storage.findAny(id).orElseThrow();
        ObjectNode tree=mapper.valueToTree(definition);
        ObjectNode defaults=(ObjectNode)tree.path("defaultContext");
        ObjectNode scope=defaults.path("scope").isObject()?(ObjectNode)defaults.path("scope"):defaults.putObject("scope").put("mode","ALL");
        scope.put("workspaceId",collaboration.workspaceId().toString()).put("accountId",dashboard.accountId().toString());
        for (var node:tree.path("widgets")) {
            ObjectNode widget=(ObjectNode)node;
            ObjectNode binding=widget.path("binding").isObject()?(ObjectNode)widget.path("binding"):widget.putObject("binding").put("mode","ALL");
            binding.put("workspaceId",collaboration.workspaceId().toString()).put("accountId",dashboard.accountId().toString());
        }
        return mapper.convertValue(tree,DashboardDefinition.class);
    }
    public DashboardDefinition viewerDefinition(DashboardActor actor,UUID id,DashboardDefinition definition) {
        var collaboration=audience.collaboration(id);
        if (collaboration==null) return definition;
        Dashboard dashboard=require(actor,id,"VIEW");
        Set<UUID> current;
        try {current=authority.resources(collaboration.workspaceId(),dashboard.accountId(),actor.userId(),"device.read").deviceIds();}
        catch(DashboardFailure denied) {if(!"NO_ACCESS".equals(denied.code())) throw denied;current=Set.of();}
        final Set<UUID> allowed=current;
        ObjectNode tree=mapper.valueToTree(context(id,definition));
        for (var node:tree.path("widgets")) {
            ObjectNode widget=(ObjectNode)node;
            ObjectNode binding=(ObjectNode)widget.path("binding");
            var originalIds=binding.path("deviceIds").deepCopy();
            var ids=binding.putArray("deviceIds");
            originalIds.forEach(device->{if (allowed.contains(UUID.fromString(device.asText()))) ids.add(device.asText());});
            // A viewer receives no hidden binding IDs, resource labels or private navigation paths.
            binding.remove("locationIds");
            if (!actor.userId().equals(dashboard.ownerUserId()) || !actor.accountId().equals(dashboard.accountId())) {
                widget.put("title",widget.path("type").asText());
                widget.set("navigation",mapper.createObjectNode());
                widget.set("payload",mapper.createObjectNode());
                if (com.voltcanary.dashboard.query.WidgetManifests.configuredEvidence(widget.path("type").asText())) {
                    widget.set("query", mapper.createObjectNode());
                }
            }
            var query=widget.path("query").isObject()?(ObjectNode)widget.path("query"):widget.putObject("query");
            if (com.voltcanary.dashboard.query.PositionWidgets.contains(widget.path("type").asText()) && ids.isEmpty()) query.remove("sourceId");
            if (query.path("vehicleSources").isObject()) {
                ObjectNode original=(ObjectNode)query.path("vehicleSources").deepCopy();
                ObjectNode visible=query.putObject("vehicleSources");
                original.fields().forEachRemaining(entry -> { if (allowed.contains(UUID.fromString(entry.getKey()))) visible.set(entry.getKey(),entry.getValue()); });
            }
            if (query.path("series").isArray()) {
                var original=query.path("series").deepCopy();
                var series=query.putArray("series");
                original.forEach(s->{if(allowed.contains(UUID.fromString(s.path("deviceId").asText()))) series.add(s);});
            }
        }
        ((ObjectNode)tree.path("defaultContext")).putObject("scope").put("mode","ALL").put("workspaceId",collaboration.workspaceId().toString()).put("accountId",dashboard.accountId().toString());
        tree.set("sections",mapper.createArrayNode());
        return mapper.convertValue(tree,DashboardDefinition.class);
    }
    private String action(String type) {
        return switch (type) {
            case "risk-list" -> "analytics.read";
            case "prediction-evidence" -> "ml.read";
            case "incident-list" -> "incident.read";
            case "maintenance-agenda" -> "maintenance.read";
            case "estimated-cost" -> "tariff.read";
            case "uptime-or-cycle" -> "operational.read";
            default -> "telemetry.read";
        };
    }
}
