package com.voltcanary.device;

import com.voltcanary.common.security.WorkspaceAuthorization.Membership;
import com.voltcanary.device.workspace.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkspaceResourceAccessTest {
    final UUID workspace=UUID.randomUUID(),account=UUID.randomUUID(),user=UUID.randomUUID(),team=UUID.randomUUID(),device=UUID.randomUUID();
    final CurrentWorkspaceMembership membership=mock(CurrentWorkspaceMembership.class);
    final ResourceGrantRepository repository=mock(ResourceGrantRepository.class);
    final WorkspaceResourceAccess access=new WorkspaceResourceAccess(membership,repository);
    void active(){when(membership.require(workspace,user)).thenReturn(new Membership(workspace,user,"MEMBER",1,List.of(team)));when(repository.version(workspace,account,false)).thenReturn(1L);when(repository.activeActor(user)).thenReturn(true);}
    @Test void intersectsCurrentTeamMembershipAndActionWithoutOwnerOrRoleBypass(){
        active();
        var read=new ResourceGrantRepository.Grant(UUID.randomUUID(),account,team,"TEAM","telemetry.read","DEVICE",device);
        var write=new ResourceGrantRepository.Grant(UUID.randomUUID(),account,user,"USER","maintenance.write","DEVICE",device);
        when(repository.grants(workspace,account)).thenReturn(List.of(read,write));
        when(repository.devices(eq(account),anyList())).thenAnswer(i->((List<?>)i.getArgument(1)).isEmpty()?Set.of():Set.of(device));
        assertThat(access.resolve(workspace,account,user,"telemetry.read").deviceIds()).containsExactly(device);
        assertThat(access.resolve(workspace,account,user,"incident.resolve").deviceIds()).isEmpty();
        when(membership.require(workspace,user)).thenReturn(new Membership(workspace,user,"OWNER",2,List.of()));
        assertThat(access.resolve(workspace,account,user,"telemetry.read").deviceIds()).isEmpty();
        assertThat(access.resolve(workspace,account,user,"maintenance.write").deviceIds()).containsExactly(device);
    }
    @Test void revocationChangesFingerprintAndDisabledAssociationFailsClosed(){
        active();when(repository.grants(workspace,account)).thenReturn(List.of());when(repository.devices(eq(account),anyList())).thenReturn(Set.of(device));
        String before=access.resolve(workspace,account,user,"telemetry.read").fingerprint();
        when(repository.version(workspace,account,false)).thenReturn(2L);when(repository.devices(eq(account),anyList())).thenReturn(Set.of());
        var after=access.resolve(workspace,account,user,"telemetry.read");assertThat(after.fingerprint()).isNotEqualTo(before);assertThat(after.deviceIds()).isEmpty();
        when(repository.version(workspace,account,false)).thenReturn(null);
        assertThatThrownBy(()->access.resolve(workspace,account,user,"telemetry.read")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void membershipRemovalAndActorDisableCannotUseOldDecision(){
        active();when(repository.activeActor(user)).thenReturn(false);
        assertThatThrownBy(()->access.resolve(workspace,account,user,"telemetry.read")).isInstanceOf(ResponseStatusException.class);
        when(repository.activeActor(user)).thenReturn(true);when(membership.require(workspace,user)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->access.resolve(workspace,account,user,"telemetry.read")).isInstanceOf(ResponseStatusException.class);
        verify(repository,never()).devices(any(),any());
    }
    @Test void personalOwnerCanWithdrawExistingConsentAfterDepartureWithoutRejoining(){
        when(repository.owner(account,user)).thenReturn(true);when(repository.associated(workspace,account)).thenReturn(true);
        access.associate(user,workspace,account,true,false);
        verify(membership,never()).require(any(),any());verify(repository).associate(workspace,account,user,false);
        when(repository.associated(workspace,account)).thenReturn(false);
        assertThatThrownBy(()->access.associate(user,workspace,account,true,false)).isInstanceOf(ResponseStatusException.class);
    }
}
