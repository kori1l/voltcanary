package com.voltcanary.identity.workspace;

import com.voltcanary.identity.workspace.WorkspaceMembership.Member;
import com.voltcanary.identity.workspace.WorkspaceMembership.MemberRequest;
import com.voltcanary.identity.workspace.WorkspaceMembership.Snapshot;
import com.voltcanary.identity.workspace.WorkspaceMembership.Team;
import com.voltcanary.identity.workspace.WorkspaceMembership.TeamRequest;
import com.voltcanary.identity.workspace.WorkspaceMembership.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceAdministration {
    private final WorkspaceRepository repository;
    public WorkspaceAdministration(WorkspaceRepository repository) {
        this.repository = repository;
    }
    public List<Workspace> list(UUID actor) {
        require(repository.activeUser(actor),HttpStatus.FORBIDDEN);
        return repository.list(actor);
    }
    public boolean teamExists(UUID workspace,UUID team) {
        return repository.teamExists(workspace,team);
    }
    @Transactional
    public Workspace create(UUID actor, String name) {
        require(repository.activeUser(actor),HttpStatus.FORBIDDEN);
        validName(name);
        require(repository.list(actor).size()<20,HttpStatus.UNPROCESSABLE_ENTITY);
        UUID id=UUID.randomUUID();
        repository.create(id,name,actor);
        repository.changed(id,actor,"CREATED",actor);
        return repository.get(id,false);
    }
    public Snapshot snapshot(UUID actor, UUID workspace) {
        Member member=repository.member(workspace,actor).orElseThrow(() -> denied(HttpStatus.FORBIDDEN));
        Workspace value=repository.get(workspace,false);
        require(value!=null,HttpStatus.FORBIDDEN);
        return new Snapshot(workspace,actor,member.role(),value.version(),repository.teamIds(workspace,actor));
    }
    public List<Member> members(UUID actor, UUID workspace) {
        manager(actor,workspace,false);
        return repository.members(workspace);
    }
    @Transactional
    public void saveMember(UUID actor, UUID workspace, MemberRequest request) {
        Snapshot admin=manager(actor,workspace,true);
        require(request!=null && request.reviewed() && request.userId()!=null && List.of("MEMBER","MANAGER").contains(request.role()),HttpStatus.UNPROCESSABLE_ENTITY);
        require(repository.activeUser(request.userId()),HttpStatus.UNPROCESSABLE_ENTITY);
        require(repository.members(workspace).size()<200 || repository.member(workspace,request.userId()).isPresent(),HttpStatus.UNPROCESSABLE_ENTITY);
        require(!request.userId().equals(repository.get(workspace,false).ownerUserId()),HttpStatus.CONFLICT);
        require("OWNER".equals(admin.role()) || "MEMBER".equals(request.role()),HttpStatus.FORBIDDEN);
        repository.saveMember(workspace,request.userId(),request.role(),"ACTIVE");
        repository.changed(workspace,actor,"MEMBER_REVIEWED",request.userId());
    }
    @Transactional
    public void remove(UUID actor, UUID workspace, UUID user) {
        Snapshot admin=manager(actor,workspace,true);
        require(!user.equals(repository.get(workspace,false).ownerUserId()),HttpStatus.CONFLICT);
        Member target=repository.member(workspace,user).orElseThrow(() -> denied(HttpStatus.NOT_FOUND));
        require("OWNER".equals(admin.role()) || "MEMBER".equals(target.role()),HttpStatus.FORBIDDEN);
        repository.saveMember(workspace,user,target.role(),"REMOVED");
        repository.changed(workspace,actor,"MEMBER_REMOVED",user);
    }
    public List<Team> teams(UUID actor, UUID workspace) {
        manager(actor,workspace,false);
        return repository.teams(workspace);
    }
    @Transactional
    public UUID addTeam(UUID actor, UUID workspace, TeamRequest request) {
        manager(actor,workspace,true);
        require(request!=null && request.reviewed() && request.userIds()!=null && request.userIds().size()<=200 && request.userIds().stream().distinct().count()==request.userIds().size(),HttpStatus.UNPROCESSABLE_ENTITY);
        validName(request.name());
        require(repository.teams(workspace).size()<100,HttpStatus.UNPROCESSABLE_ENTITY);
        request.userIds().forEach(user -> snapshot(user,workspace));
        UUID id=repository.addTeam(workspace,request.name(),request.userIds());
        repository.changed(workspace,actor,"TEAM_CREATED",id);
        return id;
    }
    @Transactional
    public void transfer(UUID actor, UUID workspace, UUID user, boolean reviewed) {
        Snapshot admin=manager(actor,workspace,true);
        require(reviewed && "OWNER".equals(admin.role()),HttpStatus.FORBIDDEN);
        snapshot(user,workspace);
        UUID old=repository.get(workspace,false).ownerUserId();
        require(!old.equals(user),HttpStatus.CONFLICT);
        repository.saveMember(workspace,old,"MEMBER","REMOVED");
        repository.saveMember(workspace,user,"OWNER","ACTIVE");
        repository.transfer(workspace,user);
        repository.changed(workspace,actor,"OWNER_TRANSFERRED",user);
    }
    public List<Map<String,Object>> audit(UUID actor, UUID workspace) {
        manager(actor,workspace,false);
        return repository.audit(workspace);
    }
    private Snapshot manager(UUID actor, UUID workspace, boolean lock) {
        if (lock) require(repository.get(workspace,true)!=null,HttpStatus.NOT_FOUND);
        Snapshot snapshot=snapshot(actor,workspace);
        require(List.of("OWNER","MANAGER").contains(snapshot.role()),HttpStatus.FORBIDDEN);
        return snapshot;
    }
    private void validName(String name) {
        require(name!=null && !name.isBlank() && name.length()<=80,HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private void require(boolean valid, HttpStatus status) {
        if (!valid) throw denied(status);
    }
    private ResponseStatusException denied(HttpStatus status) {
        return new ResponseStatusException(status,"Workspace operation is unavailable for this actor or input");
    }
}
