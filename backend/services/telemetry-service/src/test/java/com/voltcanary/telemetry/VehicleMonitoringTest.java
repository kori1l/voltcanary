package com.voltcanary.telemetry;

import com.voltcanary.telemetry.vehicle.VehicleMonitoringRepository;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringSettings;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={"app.kafka.consume-enabled=false","app.kafka.publish-enabled=false","app.telemetry.reconciliation.enabled=false","management.health.redis.enabled=false"})
class VehicleMonitoringTest {
    @Autowired VehicleMonitoringRepository repository;
    @Test void persistsValidatedSettingsByOwnerAndDevice() {
        UUID user=UUID.randomUUID(),device=UUID.randomUUID();
        var settings=new VehicleMonitoringSettings(60,1,List.of(new VehicleMonitoringSettings.Zone(UUID.randomUUID(),"Depot",44.8,20.4,200)));
        repository.save(user,device,settings);
        assertThat(repository.get(user,device)).isEqualTo(settings);
        assertThat(repository.get(UUID.randomUUID(),device)).isEqualTo(VehicleMonitoringSettings.defaults());
        repository.save(user,device,VehicleMonitoringSettings.defaults());
        assertThat(repository.get(user,device).zones()).isEmpty();
        assertThatThrownBy(()->repository.save(user,device,new VehicleMonitoringSettings(0,1,List.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->repository.save(user,device,new VehicleMonitoringSettings(60,1,List.of(new VehicleMonitoringSettings.Zone(UUID.randomUUID(),"Depot",91,20,200))))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
