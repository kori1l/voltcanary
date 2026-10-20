package com.voltcanary.dashboard.sharing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardAudienceRepository {
    public record Audience(UUID subjectId,String subjectKind,List<String> capabilities) {
    }
    public record Collaboration(UUID dashboardId,UUID workspaceId,long version) {
    }
    private final JdbcTemplate jdbc;
    public DashboardAudienceRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }
    public Collaboration collaboration(UUID dashboard) {
        return jdbc.query("SELECT * FROM dashboard_workspaces WHERE dashboard_id=?",(r,i)->new Collaboration(dashboard,r.getObject("workspace_id",UUID.class),r.getLong("version")),dashboard).stream().findFirst().orElse(null);
    }
    public void lock(UUID dashboard) {
        jdbc.query("SELECT id FROM dashboards WHERE id=? FOR UPDATE",(r,i)->r.getObject("id",UUID.class),dashboard);
    }
    public void attach(UUID dashboard,UUID workspace) {
        jdbc.update("INSERT INTO dashboard_workspaces(dashboard_id,workspace_id) VALUES (?,?)",dashboard,workspace);
    }
    public List<Audience> audience(UUID dashboard) {
        var rows=jdbc.queryForList("SELECT subject_id,subject_kind,capability FROM dashboard_grants WHERE dashboard_id=? ORDER BY subject_kind,subject_id,capability",dashboard);
        Map<String,Audience> result=new java.util.LinkedHashMap<>();
        rows.forEach(row->{
            UUID subject=(UUID)row.get("subject_id");
            String kind=(String)row.get("subject_kind");
            Audience entry=result.computeIfAbsent(kind+subject,key->new Audience(subject,kind,new java.util.ArrayList<>()));
            entry.capabilities().add((String)row.get("capability"));
        });
        return List.copyOf(result.values());
    }
    public void audience(UUID dashboard,List<Audience> audience) {
        jdbc.update("DELETE FROM dashboard_grants WHERE dashboard_id=?",dashboard);
        audience.forEach(recipient->recipient.capabilities().forEach(capability->jdbc.update("INSERT INTO dashboard_grants(dashboard_id,subject_id,subject_kind,capability) VALUES (?,?,?,?)",dashboard,recipient.subjectId(),recipient.subjectKind(),capability)));
        jdbc.update("UPDATE dashboard_workspaces SET version=version+1 WHERE dashboard_id=?",dashboard);
    }
    public List<UUID> candidates(UUID user) {
        return jdbc.query("SELECT DISTINCT w.dashboard_id FROM dashboard_workspaces w JOIN dashboards d ON d.id=w.dashboard_id LEFT JOIN dashboard_grants g ON g.dashboard_id=w.dashboard_id WHERE d.status='ACTIVE' AND (d.owner_user_id=? OR g.subject_kind='USER' AND g.subject_id=? OR g.subject_kind='TEAM') ORDER BY w.dashboard_id LIMIT 100",(r,i)->r.getObject("dashboard_id",UUID.class),user,user);
    }
    public void transfer(UUID dashboard,UUID user) {
        jdbc.update("UPDATE dashboards SET owner_user_id=?,updated_at=now() WHERE id=?",user,dashboard);
        jdbc.update("UPDATE dashboard_workspaces SET version=version+1 WHERE dashboard_id=?",dashboard);
    }
    public void audit(UUID dashboard,UUID actor,String action,UUID subject) {
        jdbc.update("INSERT INTO dashboard_sharing_audit(id,dashboard_id,actor_id,action,subject_id) VALUES (?,?,?,?,?)",UUID.randomUUID(),dashboard,actor,action,subject);
    }
    public List<Map<String,Object>> audit(UUID dashboard) {
        return jdbc.queryForList("SELECT actor_id,action,subject_id,occurred_at FROM dashboard_sharing_audit WHERE dashboard_id=? ORDER BY occurred_at DESC LIMIT 100",dashboard);
    }
}
