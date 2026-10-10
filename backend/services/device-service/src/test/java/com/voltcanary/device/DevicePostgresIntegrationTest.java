package com.voltcanary.device;

import com.voltcanary.device.dto.AccountResponse;
import com.voltcanary.device.dto.DeviceResponse;
import com.voltcanary.device.dto.MaintenanceOperationResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false"
        })
class DevicePostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired private com.voltcanary.device.repository.DashboardMaintenanceRepository dashboardMaintenance;

    @Test
    void energyMeterRevisionsAreOwnerScopedOrderedAndRejectTopologyCycles() {
        UUID user=UUID.randomUUID();
        HttpHeaders owner=headersFor(user,"energy-owner@example.invalid");
        AccountResponse account=restTemplate.exchange("/accounts/current",HttpMethod.GET,new HttpEntity<>(owner),AccountResponse.class).getBody();
        HttpHeaders foreign=headersFor(UUID.randomUUID(),"energy-foreign@example.invalid");
        restTemplate.exchange("/accounts/current",HttpMethod.GET,new HttpEntity<>(foreign),AccountResponse.class);
        UUID type=jdbc.queryForObject("SELECT id FROM device_types WHERE code='FRIDGE'",UUID.class);
        UUID parentDevice=UUID.randomUUID(),childDevice=UUID.randomUUID();
        for(UUID id:new UUID[]{parentDevice,childDevice}) {
            jdbc.update("INSERT INTO devices(id,account_id,device_type_id,name,status,created_at,updated_at) VALUES (?,?,?,'DEMO meter','ACTIVE',now(),now())",id,account.id(),type);
        }
        UUID parent=UUID.randomUUID(),child=UUID.randomUUID();
        var sourceBody=java.util.Map.of("sourceType","SMART_PLUG","protocol","REST","externalId","DEMO-meter-source");
        String parentSource=restTemplate.exchange("/devices/"+parentDevice+"/data-sources",HttpMethod.POST,new HttpEntity<>(sourceBody,owner),com.voltcanary.device.dto.DataSourceResponse.class).getBody().id().toString();
        String childSource=restTemplate.exchange("/devices/"+childDevice+"/data-sources",HttpMethod.POST,new HttpEntity<>(sourceBody,owner),com.voltcanary.device.dto.DataSourceResponse.class).getBody().id().toString();
        java.time.Instant start=java.time.Instant.parse("2026-10-08T00:00:00Z");
        var initial=new com.voltcanary.device.dto.EnergyMeterRequest(0,parent,null,parentSource,"COUNTER_DELTA",900,start,java.util.List.of());
        String path="/devices/"+parentDevice+"/energy-meter";
        var created=restTemplate.exchange(path,HttpMethod.PUT,new HttpEntity<>(initial,owner),com.voltcanary.common.web.EnergyMeterDefinition.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().revision()).isEqualTo(1);
        assertThat(restTemplate.exchange(path,HttpMethod.PUT,new HttpEntity<>(initial,owner),String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(restTemplate.exchange(path,HttpMethod.GET,new HttpEntity<>(foreign),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(path,HttpMethod.PUT,new HttpEntity<>(initial,foreign),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var foreignSource=new com.voltcanary.device.dto.EnergyMeterRequest(0,child,parent,parentSource,"POWER_INTEGRAL",900,start,java.util.List.of());
        assertThat(restTemplate.exchange("/devices/"+childDevice+"/energy-meter",HttpMethod.PUT,new HttpEntity<>(foreignSource,owner),String.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var childRequest=new com.voltcanary.device.dto.EnergyMeterRequest(0,child,parent,childSource,"POWER_INTEGRAL",900,start,java.util.List.of());
        assertThat(restTemplate.exchange("/devices/"+childDevice+"/energy-meter",HttpMethod.PUT,new HttpEntity<>(childRequest,owner),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var cycle=new com.voltcanary.device.dto.EnergyMeterRequest(1,parent,child,parentSource,"COUNTER_DELTA",900,start.plusSeconds(3600),java.util.List.of());
        assertThat(restTemplate.exchange(path,HttpMethod.PUT,new HttpEntity<>(cycle,owner),String.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var replacement=new com.voltcanary.device.dto.EnergyMeterRequest(1,UUID.randomUUID(),null,parentSource,"COUNTER_DELTA",900,start.plusSeconds(3600),java.util.List.of());
        assertThat(restTemplate.exchange(path,HttpMethod.PUT,new HttpEntity<>(replacement,owner),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var history=restTemplate.exchange(path,HttpMethod.GET,new HttpEntity<>(owner),com.voltcanary.common.web.EnergyMeterDefinition[].class).getBody();
        assertThat(history).hasSize(2);
        assertThat(history[0].effectiveTo()).isEqualTo(start.plusSeconds(3600));
        assertThat(history[1].physicalMeterId()).isEqualTo(replacement.physicalMeterId());
        jdbc.update("UPDATE accounts SET status='DISABLED' WHERE id=?",account.id());
        assertThat(restTemplate.exchange(path,HttpMethod.GET,new HttpEntity<>(owner),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void laterDevicesAndFilteredMaintenanceRemainReachableAndOwnerScoped() {
        UUID user = UUID.randomUUID();
        HttpHeaders headers = headersFor(user, "pagination@example.com");
        AccountResponse account = restTemplate.exchange("/accounts/current", HttpMethod.GET, new HttpEntity<>(headers), AccountResponse.class).getBody();
        UUID location = UUID.randomUUID();
        UUID otherLocation = UUID.randomUUID();
        for (UUID id : new UUID[]{location, otherLocation}) {
            jdbc.update("insert into locations (id,account_id,name,created_at,updated_at) values (?,?,?,now(),now())", id, account.id(), "Page location");
        }
        UUID type = jdbc.queryForObject("select id from device_types where code='FRIDGE'", UUID.class);
        long prefix = UUID.randomUUID().getMostSignificantBits();
        UUID first = null;
        UUID last = null;
        for (int index = 1; index <= 120; index++) {
            UUID id = new UUID(prefix, index);
            if (index == 1) first = id;
            if (index == 120) last = id;
            jdbc.update("insert into devices (id,account_id,location_id,device_type_id,name,status,created_at,updated_at) values (?,?,?,?,?,'ACTIVE','2026-01-01','2026-01-01')", id, account.id(), index == 120 ? otherLocation : location, type, "Device " + index);
        }
        ResponseEntity<DeviceResponse[]> page = restTemplate.exchange("/devices?offset=100&limit=20", HttpMethod.GET, new HttpEntity<>(headers), DeviceResponse[].class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).hasSize(20).extracting(DeviceResponse::name).contains("Device 101", "Device 120");
        UUID operationPrefix = UUID.randomUUID();
        for (int index = 1; index <= 225; index++) {
            UUID operation = new UUID(operationPrefix.getMostSignificantBits(), index);
            jdbc.update("insert into maintenance_operations (id,account_id,title,start_at,end_at,created_at,updated_at) values (?,?,?,'2026-01-01','2026-01-02',now(),now())", operation, account.id(), "Operation " + index);
            jdbc.update("insert into maintenance_windows (id,account_id,device_id,operation_id,title,start_at,end_at,created_at,updated_at) values (?,?,?,?,?,'2026-01-01','2026-01-02',now(),now())", UUID.randomUUID(), account.id(), index > 210 ? last : first, operation, "Operation " + index);
        }
        var later = restTemplate.exchange("/maintenance-operations?offset=200&limit=25", HttpMethod.GET, new HttpEntity<>(headers), MaintenanceOperationResponse[].class);
        assertThat(later.getBody()).hasSize(25).extracting(MaintenanceOperationResponse::title).contains("Operation 201", "Operation 225");
        var filtered = restTemplate.exchange("/maintenance-operations?locationId=" + otherLocation + "&status=FINISHED&offset=10&limit=10", HttpMethod.GET, new HttpEntity<>(headers), MaintenanceOperationResponse[].class);
        assertThat(filtered.getBody()).hasSize(5).extracting(MaintenanceOperationResponse::title).containsExactly("Operation 221", "Operation 222", "Operation 223", "Operation 224", "Operation 225");
        var membership = restTemplate.exchange("/maintenance-operations?deviceIds=" + last + "&offset=10&limit=10", HttpMethod.GET, new HttpEntity<>(headers), MaintenanceOperationResponse[].class);
        assertThat(membership.getBody()).hasSize(5);
        var foreign = restTemplate.exchange("/maintenance-operations?locationId=" + otherLocation, HttpMethod.GET, new HttpEntity<>(headersFor(UUID.randomUUID(), "foreign@example.com")), MaintenanceOperationResponse[].class);
        assertThat(foreign.getBody()).isEmpty();
        var empty = restTemplate.exchange("/maintenance-operations?offset=225&limit=10", HttpMethod.GET, new HttpEntity<>(headers), MaintenanceOperationResponse[].class);
        assertThat(empty.getBody()).isEmpty();
        var from = java.time.Instant.parse("2026-01-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-01-03T00:00:00Z");
        var readRequest = new com.voltcanary.common.web.DashboardOwnerReadRequest(user, account.id(), java.util.List.of(last),
                new com.voltcanary.common.web.DashboardOwnerReadRequest.TimeFrame(from, to, to, null, null),
                java.util.Set.of(), java.util.List.of(), 10, 10, "ACTIVE", java.util.List.of());
        var ownerRead = dashboardMaintenance.read(readRequest, java.util.List.of(last));
        assertThat(ownerRead.path("total").asInt()).isEqualTo(15);
        assertThat(ownerRead.path("rows").size()).isEqualTo(5);
        assertThat(ownerRead.path("rows").get(0).path("excludeFromAnalytics").asBoolean()).isTrue();
        assertThat(ownerRead.path("rows").get(0).path("excludeFromMl").asBoolean()).isTrue();
        assertThat(ownerRead.path("rows").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(restTemplate.postForEntity("/internal/dashboard/maintenance", readRequest, String.class).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void currentAccountIsCreatedInPostgres() {
        UUID userId = UUID.randomUUID();
        ResponseEntity<AccountResponse> response = restTemplate.exchange(
                "/accounts/current",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "device-postgres@example.com")),
                AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(userId);
    }

    private HttpHeaders headersFor(UUID userId, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-User-Email", email);
        headers.set("X-User-Role", "USER");
        return headers;
    }

    @Test
    void dashboardScopeCountsAll250AndNeverUsesSpoofedActorOrSourceSecrets() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AccountResponse account = restTemplate.exchange("/accounts/current", HttpMethod.GET,
                new HttpEntity<>(headersFor(user, "scope@example.invalid")), AccountResponse.class).getBody();
        AccountResponse foreign = restTemplate.exchange("/accounts/current", HttpMethod.GET,
                new HttpEntity<>(headersFor(other, "other@example.invalid")), AccountResponse.class).getBody();
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        jdbc.update("INSERT INTO locations(id,account_id,name,created_at,updated_at,tags_json) VALUES(?,?,?,'2026-01-01','2026-01-01','[]')",
                root, account.id(), "Root");
        jdbc.update("INSERT INTO locations(id,account_id,parent_id,name,created_at,updated_at,tags_json) VALUES(?,?,?,?,'2026-01-01','2026-01-01','[\"kitchen\"]')",
                child, account.id(), root, "Child");
        jdbc.update("INSERT INTO locations(id,account_id,name,created_at,updated_at,tags_json) VALUES(?,?,?,'2026-01-01','2026-01-01','[]')",
                outside, account.id(), "Outside");
        UUID type = jdbc.queryForObject("SELECT id FROM device_types WHERE code='FRIDGE'", UUID.class);
        UUID first = null;
        for (int index = 0; index < 250; index++) {
            UUID id = UUID.randomUUID();
            if (index == 0) first = id;
            jdbc.update("INSERT INTO devices(id,account_id,location_id,device_type_id,name,status,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE','2026-01-01','2026-01-01')",
                    id, account.id(), index % 2 == 0 ? child : outside, type, "Scope " + index);
        }
        UUID foreignDevice = UUID.randomUUID();
        jdbc.update("INSERT INTO devices(id,account_id,device_type_id,name,status,created_at,updated_at) VALUES(?,?,?,'Hidden foreign name','ACTIVE','2026-01-01','2026-01-01')",
                foreignDevice, foreign.id(), type);
        jdbc.update("""
                INSERT INTO data_sources(id,device_id,name,type,source_type,source_id,protocol,enabled,status,
                adapter_config_json,created_at,updated_at) VALUES(?,?,'Source','SIMULATOR','SIMULATOR','scope-fixture','SIM',true,'ACTIVE',
                '{"pollIntervalMs":10000,"accessToken":"must-not-leak"}','2026-01-01','2026-01-01')
                """, UUID.randomUUID(), first);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(new com.voltcanary.common.security.JwtAccessTokenService("test", "voltcanary-identity", "voltcanary-api")
                .create(user.toString(), "scope@example.invalid", "USER", 900));
        headers.set("X-Internal-Service-Token", "dev-internal-token");
        headers.set("X-User-Id", other.toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var all = restTemplate.exchange("/internal/dashboard/scope", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("mode", "ALL", "accountId", foreign.id()), headers),
                com.voltcanary.device.dto.DashboardScopeResponse.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody().evaluatedCount()).isEqualTo(250);
        assertThat(all.getBody().requestedCount()).isEqualTo(250);
        assertThat(all.getBody().accountId()).isEqualTo(account.id());
        assertThat(all.getBody().devices()).hasSize(250);
        assertThat(all.getBody().deviceTagsSupported()).isFalse();
        UUID selected = first;
        assertThat(all.getBody().devices().stream().filter(device -> device.id().equals(selected)).findFirst().orElseThrow().cadenceSeconds()).isEqualTo(10L);
        var raw = restTemplate.exchange("/internal/dashboard/scope", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("mode", "ALL"), headers), String.class);
        assertThat(raw.getBody()).doesNotContain("must-not-leak", "adapter_config_json", "Hidden foreign name");
        var subtree = restTemplate.exchange("/internal/dashboard/scope", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("mode", "DYNAMIC", "locationIds", java.util.List.of(root), "includeSubtree", true), headers),
                com.voltcanary.device.dto.DashboardScopeResponse.class);
        assertThat(subtree.getBody().evaluatedCount()).isEqualTo(125);
        var tagged = restTemplate.exchange("/internal/dashboard/scope", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("locationTags", java.util.List.of("kitchen")), headers),
                com.voltcanary.device.dto.DashboardScopeResponse.class);
        assertThat(tagged.getBody().evaluatedCount()).isEqualTo(125);
        var denied = restTemplate.exchange("/internal/dashboard/scope", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("mode", "FIXED", "deviceIds", java.util.List.of(foreignDevice)), headers),
                com.voltcanary.device.dto.DashboardScopeResponse.class);
        assertThat(denied.getBody().deniedBindingCount()).isEqualTo(1);
        assertThat(denied.getBody().devices()).isEmpty();
        jdbc.update("UPDATE accounts SET status='DISABLED' WHERE id=?", account.id());
        assertThat(restTemplate.exchange("/internal/dashboard/scope", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("mode", "ALL"), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
