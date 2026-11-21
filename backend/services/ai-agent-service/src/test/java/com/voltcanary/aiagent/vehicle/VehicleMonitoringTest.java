package com.voltcanary.aiagent.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleMonitoringTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final ProtocolValidator validator=new ProtocolValidator(mapper);
    @Test void selectedMapPointIsExplicitBoundedContext(){
        assertThat(VehicleMonitoringPoint.parse(VehicleMonitoringPoint.selection(Map.of("latitude",60,"longitude",24))))
                .isEqualTo(new VehicleMonitoringPoint(60,24));
        assertThatThrownBy(()->VehicleMonitoringPoint.selection(Map.of("latitude",91,"longitude",24))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->VehicleMonitoringPoint.selection(Map.of("latitude","60","longitude",24))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void manifestIsRegisteredWithOneExistingDeviceBinding(){
        var registry=new SkillRegistry(validator,List.of(VehicleMonitoringSkill.create(mapper,validator)));
        assertThat(registry.catalogue(Set.of(VehicleMonitoringSkill.INTENT),Set.of())).hasSize(1);
    }
    @Test void editsPreserveOmittedZonesAndThresholdsAndRejectUnknownIds()throws Exception{
        var current=mapper.readTree("{\"speedLimitKmh\":80,\"idleMinutes\":2,\"zones\":[{\"id\":\"old\",\"name\":\"Depot\",\"latitude\":60,\"longitude\":24,\"radiusMetres\":100}]}");
        var command=mapper.readTree("{\"speedLimitKmh\":60,\"idleMinutes\":null,\"zoneEdits\":[{\"op\":\"ADD\",\"zoneId\":null,\"name\":\"Warehouse\",\"latitude\":61,\"longitude\":25,\"radiusMetres\":200}]}");
        var result=VehicleMonitoringDraft.apply(current,command,"original");
        assertThat(result.path("zones").get(0)).isEqualTo(current.path("zones").get(0));
        assertThat(result.path("idleMinutes").asInt()).isEqualTo(2);
        assertThat(result.path("zones").size()).isEqualTo(2);
        assertThat(result.path("speedLimitKmh").isDouble()).isTrue();
        assertThat(result.at("/zones/1/latitude").isDouble()).isTrue();
        assertThat(result.at("/zones/1/radiusMetres").isDouble()).isTrue();
        assertThat(VehicleMonitoringDraft.apply(current,command,"original")).isEqualTo(result);
        ((com.fasterxml.jackson.databind.node.ObjectNode)command.path("zoneEdits").get(0)).put("op","UPDATE").put("zoneId","missing");
        assertThatThrownBy(()->VehicleMonitoringDraft.apply(current,command,"original")).hasMessageContaining("existing zone");
    }
    @Test void receiptRecoveryAndReplaysUseReadsWithoutOverwritingLaterSettings()throws Exception{
        var owner=mock(VehicleMonitoringOwner.class);UUID user=UUID.randomUUID(),device=UUID.randomUUID(),id=UUID.randomUUID();
        var review=mapper.createObjectNode().put("id",id.toString()).put("deviceId",device.toString()).put("baseRevision",3).put("digest","digest").put("idempotencyKey","key").put("expiresAt","2026-01-01T00:00:00Z");
        review.set("before",mapper.readTree("{\"speedLimitKmh\":80,\"idleMinutes\":2,\"zones\":[]}"));review.set("after",mapper.readTree("{\"speedLimitKmh\":60,\"idleMinutes\":2,\"zones\":[]}"));
        var action=new AgentAction(UUID.randomUUID(),AgentTool.UPDATE_VEHICLE_MONITORING,"Review","Monitoring",true,Map.of("userId",user.toString(),"deviceId",device.toString(),"review",review));
        when(owner.read("auth",user,device,"/reviews/"+id)).thenReturn(review.deepCopy().put("status","SUCCEEDED").put("appliedRevision",4));
        var executor=new VehicleMonitoringExecutor(owner,mapper);
        var result=executor.execute(action,"auth",user,true);
        assertThat(result.get("revision")).isEqualTo(4L);assertThat(result.get("route")).asString().startsWith("/vehicles?deviceId="+device);
        assertThat(executor.execute(action,"auth",user,false)).isEqualTo(result);
        verify(owner,never()).post(anyString(),any(),any(),anyString(),any());
        assertThatThrownBy(()->executor.execute(action,"auth",UUID.randomUUID(),true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unresolvedRecoveryNeverRenewsConfirmationAuthority()throws Exception{
        var owner=mock(VehicleMonitoringOwner.class);UUID user=UUID.randomUUID(),device=UUID.randomUUID();
        var review=mapper.readTree("{\"id\":\""+UUID.randomUUID()+"\",\"deviceId\":\""+device+"\",\"baseRevision\":0,\"before\":{},\"after\":{},\"digest\":\"d\",\"idempotencyKey\":\"k\",\"expiresAt\":\"2026-01-01T00:00:00Z\"}");
        when(owner.read(eq("auth"),eq(user),eq(device),anyString())).thenReturn(((com.fasterxml.jackson.databind.node.ObjectNode)review.deepCopy()).put("status","EXPIRED"));
        var action=new AgentAction(UUID.randomUUID(),AgentTool.UPDATE_VEHICLE_MONITORING,"Review","Monitoring",true,Map.of("userId",user.toString(),"deviceId",device.toString(),"review",review));
        assertThatThrownBy(()->new VehicleMonitoringExecutor(owner,mapper).execute(action,"auth",user,true)).isInstanceOf(IllegalStateException.class);
        verify(owner,never()).post(anyString(),any(),any(),anyString(),any());
    }
}
