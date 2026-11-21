package com.voltcanary.device.workspace;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ResourceGrantRepository {
    public record Grant(UUID id, UUID accountId, UUID subjectId, String subjectKind, String action, String scopeKind, UUID resourceId) {
    }
    public record Association(UUID accountId, boolean active, long version) {
    }
    private final JdbcTemplate jdbc;

    public boolean owner(UUID account, UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE')",Boolean.class,account,actor));
    }
    public List<Association> associations(UUID workspace) {
        return jdbc.query("SELECT account_id,active,version FROM workspace_resource_accounts WHERE workspace_id=? ORDER BY account_id",(r,i)->new Association(r.getObject("account_id",UUID.class),r.getBoolean("active"),r.getLong("version")),workspace);
    }
    public Long version(UUID workspace,UUID account, boolean lock) {
        return jdbc.query("SELECT a.version FROM workspace_resource_accounts a JOIN accounts c ON c.id=a.account_id WHERE workspace_id=? AND account_id=? AND a.active=true AND c.status='ACTIVE'"+(lock?" FOR UPDATE OF a":""),(r,i)->r.getLong("version"),workspace,account).stream().findFirst().orElse(null);
    }
    public void associate(UUID workspace,UUID account,UUID actor,boolean active) {
        jdbc.update("INSERT INTO workspace_resource_accounts(workspace_id,account_id,consented_by,active) VALUES (?,?,?,?) ON CONFLICT(workspace_id,account_id) DO UPDATE SET active=excluded.active,version=workspace_resource_accounts.version+1",workspace,account,actor,active);
    }
    public boolean associated(UUID workspace,UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workspace_resource_accounts WHERE workspace_id=? AND account_id=?)",Boolean.class,workspace,account));
    }
    public boolean resourceOwned(UUID account,String kind,UUID resource) {
        String table="DEVICE".equals(kind)?"devices":"locations";
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE account_id=? AND id=?)",Boolean.class,account,resource));
    }
    public List<Grant> grants(UUID workspace,UUID account) {
        return jdbc.query("SELECT * FROM workspace_resource_grants WHERE workspace_id=? AND account_id=? ORDER BY id LIMIT 1000",(r,i)->new Grant(r.getObject("id",UUID.class),account,r.getObject("subject_id",UUID.class),r.getString("subject_kind"),r.getString("action"),r.getString("scope_kind"),r.getObject("resource_id",UUID.class)),workspace,account);
    }
    public void insert(UUID workspace,Grant grant) {
        jdbc.update("INSERT INTO workspace_resource_grants(id,workspace_id,account_id,subject_id,subject_kind,action,scope_kind,resource_id) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(workspace_id,account_id,subject_id,subject_kind,action,scope_kind,resource_id) DO NOTHING",grant.id(),workspace,grant.accountId(),grant.subjectId(),grant.subjectKind(),grant.action(),grant.scopeKind(),grant.resourceId());
    }
    public boolean remove(UUID workspace,UUID account,UUID grantId) {
        return jdbc.update("DELETE FROM workspace_resource_grants WHERE workspace_id=? AND account_id=? AND id=?",workspace,account,grantId)>0;
    }
    public Set<UUID> devices(UUID account, List<Grant> allowed) {
        Set<UUID> ids=new java.util.HashSet<>();
        for (Grant grant:allowed) {
            if ("DEVICE".equals(grant.scopeKind())) {
                jdbc.query("SELECT id FROM devices WHERE account_id=? AND id=?",r->{ids.add(r.getObject("id",UUID.class));},account,grant.resourceId());
            } else {
                jdbc.query("WITH RECURSIVE tree AS (SELECT id FROM locations WHERE account_id=? AND id=? UNION SELECT l.id FROM locations l JOIN tree t ON l.parent_id=t.id WHERE l.account_id=?) SELECT d.id FROM devices d JOIN tree t ON d.location_id=t.id WHERE d.account_id=? LIMIT 501",r->{ids.add(r.getObject("id",UUID.class));},account,grant.resourceId(),account,account);
            }
        }
        return Set.copyOf(ids);
    }
    public boolean activeActor(UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE user_id=? AND status='ACTIVE')",Boolean.class,user));
    }
    public void changed(UUID workspace,UUID account,UUID actor,String action,UUID subject) {
        jdbc.update("UPDATE workspace_resource_accounts SET version=version+1 WHERE workspace_id=? AND account_id=?",workspace,account);
        jdbc.update("INSERT INTO workspace_resource_audit(id,workspace_id,account_id,actor_id,action,subject_id) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),workspace,account,actor,action,subject);
    }
    public List<Map<String,Object>> audit(UUID workspace,UUID account) {
        return jdbc.queryForList("SELECT actor_id,action,subject_id,occurred_at FROM workspace_resource_audit WHERE workspace_id=? AND account_id=? ORDER BY occurred_at DESC LIMIT 100",workspace,account);
    }
}
