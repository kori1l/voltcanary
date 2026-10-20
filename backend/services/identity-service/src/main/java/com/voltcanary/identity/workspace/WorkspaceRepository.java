package com.voltcanary.identity.workspace;

import com.voltcanary.identity.workspace.WorkspaceMembership.Member;
import com.voltcanary.identity.workspace.WorkspaceMembership.Team;
import com.voltcanary.identity.workspace.WorkspaceMembership.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbc;
    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public boolean activeUser(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND status='ACTIVE')", Boolean.class, id));
    }
    public Optional<Member> member(UUID workspace, UUID user) {
        return jdbc.query("SELECT m.* FROM workspace_members m JOIN users u ON u.id=m.user_id WHERE workspace_id=? AND user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                (r,i) -> new Member(r.getObject("user_id",UUID.class),r.getString("role"),r.getString("status")),workspace,user).stream().findFirst();
    }
    public Workspace get(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM workspaces WHERE id=?"+(lock?" FOR UPDATE":""),
                (r,i) -> new Workspace(id,r.getString("name"),r.getObject("owner_user_id",UUID.class),r.getLong("version")),id).stream().findFirst().orElse(null);
    }
    public List<Workspace> list(UUID user) {
        return jdbc.query("SELECT w.* FROM workspaces w JOIN workspace_members m ON m.workspace_id=w.id JOIN users u ON u.id=m.user_id WHERE m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' ORDER BY w.created_at,w.id LIMIT 100",
                (r,i) -> new Workspace(r.getObject("id",UUID.class),r.getString("name"),r.getObject("owner_user_id",UUID.class),r.getLong("version")),user);
    }
    public void create(UUID id, String name, UUID owner) {
        jdbc.update("INSERT INTO workspaces(id,name,owner_user_id) VALUES (?,?,?)",id,name,owner);
        saveMember(id,owner,"OWNER","ACTIVE");
    }
    public List<Member> members(UUID workspace) {
        return jdbc.query("SELECT user_id,role,status FROM workspace_members WHERE workspace_id=? ORDER BY user_id LIMIT 200",
                (r,i) -> new Member(r.getObject("user_id",UUID.class),r.getString("role"),r.getString("status")),workspace);
    }
    public void saveMember(UUID workspace, UUID user, String role, String status) {
        jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role,status) VALUES (?,?,?,?) ON CONFLICT(workspace_id,user_id) DO UPDATE SET role=excluded.role,status=excluded.status",workspace,user,role,status);
        if ("REMOVED".equals(status)) jdbc.update("DELETE FROM workspace_team_members WHERE workspace_id=? AND user_id=?",workspace,user);
    }
    public List<UUID> teamIds(UUID workspace, UUID user) {
        return jdbc.query("SELECT team_id FROM workspace_team_members WHERE workspace_id=? AND user_id=? ORDER BY team_id",(r,i) -> r.getObject("team_id",UUID.class),workspace,user);
    }
    public List<Team> teams(UUID workspace) {
        return jdbc.query("SELECT id,name FROM workspace_teams WHERE workspace_id=? ORDER BY id LIMIT 100",(r,i) -> {
            UUID id=r.getObject("id",UUID.class);
            return new Team(id,r.getString("name"),jdbc.query("SELECT user_id FROM workspace_team_members WHERE workspace_id=? AND team_id=? ORDER BY user_id",(m,j)->m.getObject("user_id",UUID.class),workspace,id));
        },workspace);
    }
    public boolean teamExists(UUID workspace,UUID team) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workspace_teams WHERE workspace_id=? AND id=?)",Boolean.class,workspace,team));
    }
    public UUID addTeam(UUID workspace, String name, List<UUID> users) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO workspace_teams(id,workspace_id,name) VALUES (?,?,?)",id,workspace,name);
        users.forEach(user -> jdbc.update("INSERT INTO workspace_team_members(workspace_id,team_id,user_id) VALUES (?,?,?)",workspace,id,user));
        return id;
    }
    public void transfer(UUID workspace, UUID owner) {
        jdbc.update("UPDATE workspaces SET owner_user_id=? WHERE id=?",owner,workspace);
    }
    public void changed(UUID workspace, UUID actor, String action, UUID subject) {
        jdbc.update("UPDATE workspaces SET version=version+1 WHERE id=?",workspace);
        jdbc.update("INSERT INTO workspace_audit(id,workspace_id,actor_id,action,subject_id) VALUES (?,?,?,?,?)",UUID.randomUUID(),workspace,actor,action,subject);
    }
    public List<java.util.Map<String,Object>> audit(UUID workspace) {
        return jdbc.queryForList("SELECT actor_id,action,subject_id,occurred_at FROM workspace_audit WHERE workspace_id=? ORDER BY occurred_at DESC LIMIT 100",workspace);
    }
}
