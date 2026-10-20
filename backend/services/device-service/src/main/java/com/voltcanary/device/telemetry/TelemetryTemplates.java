package com.voltcanary.device.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryFieldPolicy;
import com.voltcanary.device.dto.DataSourceRequest;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.DataSourceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelemetryTemplates {
    public record Template(UUID id, String name, String deviceType, int version, TelemetryFieldPolicy policy) { }
    public record Edit(String name, String deviceType, Integer version, TelemetryFieldPolicy policy) { }
    public record Target(UUID deviceId, UUID sourceId) { }
    public record Assignment(UUID templateId, List<Target> targets, String deviceType) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DataSourceService sources;

    public TelemetryTemplates(JdbcTemplate jdbc, ObjectMapper mapper, DataSourceService sources) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sources = sources;
    }

    public List<Template> list(AuthenticatedUser user) {
        return jdbc.query("select * from telemetry_field_templates where owner_id=? order by name,id", (row, index) ->
                new Template(row.getObject("id", UUID.class), row.getString("name"), row.getString("device_type"), row.getInt("version"), decode(row.getString("policy_json"))), user.id());
    }

    public Template owned(AuthenticatedUser user, UUID id) {
        return list(user).stream().filter(template -> template.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Telemetry template not found"));
    }

    @Transactional
    public Template save(AuthenticatedUser user, UUID id, Edit edit) {
        if (edit == null || edit.name() == null || edit.name().isBlank() || edit.name().length() > 80 || edit.policy() == null
                || edit.deviceType() != null && !edit.deviceType().matches("[A-Z_]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template");
        }
        String json = encode(edit.policy());
        if (id == null) {
            if (list(user).size() >= 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most fifty templates");
            id = UUID.randomUUID();
            jdbc.update("insert into telemetry_field_templates(id,owner_id,name,device_type,version,policy_json,updated_at) values(?,?,?,?,1,?,?)",
                    id, user.id(), edit.name().trim(), edit.deviceType(), json, Timestamp.from(Instant.now()));
        } else {
            Template old = owned(user, id);
            if (edit.version() == null || edit.version() != old.version()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Template changed; reload");
            int changed = jdbc.update("update telemetry_field_templates set name=?,device_type=?,version=version+1,policy_json=?,updated_at=? where id=? and owner_id=? and version=?",
                    edit.name().trim(), edit.deviceType(), json, Timestamp.from(Instant.now()), id, user.id(), old.version());
            if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Template changed; reload");
        }
        Template saved = owned(user, id);
        List<Target> assigned = ownedTargets(user, null, id);
        if (assigned.size() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A shared template supports at most five hundred sources");
        for (Target target : assigned) {
            var source = sources.get(user, target.deviceId(), target.sourceId());
            if (source.mapping() != null && id.toString().equals(source.mapping().get("telemetryTemplateId"))) apply(user, target, saved);
        }
        return saved;
    }

    @Transactional
    public int assign(AuthenticatedUser user, Assignment request) {
        Template template = request.templateId() == null ? null : owned(user, request.templateId());
        if (request.deviceType() != null && !request.deviceType().matches("[A-Z_]{1,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device type");
        List<Target> targets = request.deviceType() == null ? request.targets() : ownedTargets(user, request.deviceType());
        if (targets == null || targets.isEmpty() || targets.size() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select one to five hundred sources");
        for (Target target : targets) sources.get(user, target.deviceId(), target.sourceId());
        for (Target target : targets) apply(user, target, template);
        return targets.size();
    }

    private void apply(AuthenticatedUser user, Target target, Template template) {
        var source = sources.get(user, target.deviceId(), target.sourceId());
        Map<String, Object> mapping = new LinkedHashMap<>(source.mapping() == null ? Map.of() : source.mapping());
        mapping.remove("telemetryTemplateId");
        mapping.remove("telemetryPolicy");
        if (template != null) {
            mapping.put("telemetryTemplateId", template.id().toString());
            mapping.put("telemetryPolicy", mapper.convertValue(template.policy(), Map.class));
        }
        sources.update(user, target.deviceId(), target.sourceId(), new DataSourceRequest(com.voltcanary.device.domain.DataSourceType.valueOf(source.sourceType()), com.voltcanary.device.domain.DataSourceProtocol.valueOf(source.protocol()), source.externalId(), mapping,
                source.adapterConfig(), source.credentialReferences(), source.homeRegion()));
    }

    private List<Target> ownedTargets(AuthenticatedUser user, String deviceType) {
        return ownedTargets(user, deviceType, null);
    }

    private List<Target> ownedTargets(AuthenticatedUser user, String deviceType, UUID templateId) {
        String reference = templateId == null ? "" : templateId.toString();
        return jdbc.query("""
                select d.id as device_id,s.id as source_id from data_sources s
                join devices d on d.id=s.device_id join accounts a on a.id=d.account_id
                join device_types t on t.id=d.device_type_id
                where a.user_id=? and a.status='ACTIVE' and (?='' or t.code=?) and (?='' or s.mapping_json like ?)
                order by d.id,s.id limit 501
                """, (row, index) -> new Target(row.getObject("device_id", UUID.class), row.getObject("source_id", UUID.class)), user.id(), deviceType == null ? "" : deviceType, deviceType == null ? "" : deviceType, reference, "%" + reference + "%");
    }

    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid policy", failure); }
    }

    private TelemetryFieldPolicy decode(String json) {
        try { return mapper.readValue(json, TelemetryFieldPolicy.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid stored policy", failure); }
    }
}
