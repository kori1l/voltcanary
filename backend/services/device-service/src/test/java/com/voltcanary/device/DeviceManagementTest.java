package com.voltcanary.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.account.AccountResponse;
import com.voltcanary.device.analyticsconfig.DeviceAnalyticsConfigResponse;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.datasource.DataSourceResponse;
import com.voltcanary.device.datasource.SourceTokenResponse;
import com.voltcanary.device.device.DeviceResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.location.LocationResponse;
import com.voltcanary.device.sensor.SensorResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "app.kafka.publish-enabled=false"
        })
class DeviceManagementTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceRegistryEventPublisher eventPublisher;

    @MockitoBean
    private DeviceAuditService auditService;

    @Test
    void createsAccountImplicitly() {
        ResponseEntity<AccountResponse> response = restTemplate.exchange(
                "/accounts/current",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(UUID.randomUUID(), "account@example.com")),
                AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isNotNull();
        assertThat(response.getBody().name()).contains("account@example.com");
        assertThat(response.getBody().status()).isEqualTo("ACTIVE");
    }

    @Test
    void syncsAccountLifecycleFromInternalContract() {
        UUID userId = UUID.randomUUID();
        ResponseEntity<AccountResponse> created = restTemplate.exchange(
                "/accounts/current",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "lifecycle-account@example.com")),
                AccountResponse.class);

        ResponseEntity<String> disabled = restTemplate.exchange(
                "/internal/accounts/users/" + userId + "/lifecycle",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", "lifecycle-account@example.com", "status", "DISABLED"),
                        internalHeaders()),
                String.class);
        ResponseEntity<String> currentWhileDisabled = restTemplate.exchange(
                "/accounts/current",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "lifecycle-account@example.com")),
                String.class);
        ResponseEntity<String> reactivated = restTemplate.exchange(
                "/internal/accounts/users/" + userId + "/lifecycle",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", "lifecycle-account@example.com", "status", "ACTIVE"),
                        internalHeaders()),
                String.class);
        ResponseEntity<AccountResponse> currentAfterReactivate = restTemplate.exchange(
                "/accounts/current",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "lifecycle-account@example.com")),
                AccountResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).contains("\"status\":\"DISABLED\"");
        assertThat(currentWhileDisabled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(currentWhileDisabled.getBody()).contains("account_inactive");
        assertThat(reactivated.getBody()).contains("\"status\":\"ACTIVE\"");
        assertThat(currentAfterReactivate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(currentAfterReactivate.getBody().status()).isEqualTo("ACTIVE");
    }

    @Test
    void createsAndListsLocations() {
        UUID userId = UUID.randomUUID();
        LocationResponse created = createLocation(userId, "Kitchen");

        ResponseEntity<LocationResponse[]> response = restTemplate.exchange(
                "/locations",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "locations@example.com")),
                LocationResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(LocationResponse::id).contains(created.id());
    }

    @Test
    void filtersAndPagesLocations() {
        UUID userId = UUID.randomUUID();
        LocationResponse kitchen = createLocation(userId, "Kitchen north");
        createLocation(userId, "Garage");

        ResponseEntity<LocationResponse[]> response = restTemplate.exchange(
                "/locations?search=kitchen&limit=1&offset=0",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "location-filter@example.com")),
                LocationResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].id()).isEqualTo(kitchen.id());
    }

    @Test
    void storesLocationHierarchyFields() {
        UUID userId = UUID.randomUUID();
        LocationResponse building = createLocation(userId, "HQ");

        ResponseEntity<LocationResponse> response = restTemplate.exchange(
                "/locations",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "parentId", building.id().toString(),
                                "name", "Lab 1",
                                "description", "Test lab",
                                "building", "HQ",
                                "floor", "2",
                                "room", "204",
                                "zone", "north",
                                "tags", java.util.List.of("hvac", "critical")),
                        headersFor(userId, "location-hierarchy@example.com")),
                LocationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().parentId()).isEqualTo(building.id());
        assertThat(response.getBody().building()).isEqualTo("HQ");
        assertThat(response.getBody().floor()).isEqualTo("2");
        assertThat(response.getBody().room()).isEqualTo("204");
        assertThat(response.getBody().zone()).isEqualTo("north");
        assertThat(response.getBody().tags()).containsExactly("hvac", "critical");
    }

    @Test
    void createsListsAndGetsDevice() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Kitchen");
        DeviceResponse created = createDevice(userId, location.id(), "Kitchen fridge");

        ResponseEntity<DeviceResponse[]> listResponse = restTemplate.exchange(
                "/devices",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "devices@example.com")),
                DeviceResponse[].class);
        ResponseEntity<String> getResponse = restTemplate.exchange(
                "/devices/" + created.id(),
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "devices@example.com")),
                String.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).extracting(DeviceResponse::id).contains(created.id());
        assertThat(getResponse.getStatusCode()).as(getResponse.getBody()).isEqualTo(HttpStatus.OK);
        try {
            assertThat(objectMapper.readValue(getResponse.getBody(), DeviceResponse.class).type()).isEqualTo("FRIDGE");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse get device response", exception);
        }
    }

    @Test
    void filtersAndPagesDevices() {
        UUID userId = UUID.randomUUID();
        LocationResponse kitchen = createLocation(userId, "Filter kitchen");
        LocationResponse garage = createLocation(userId, "Filter garage");
        DeviceResponse fridge = createDevice(userId, kitchen.id(), "Filter fridge");
        createDevice(userId, garage.id(), "Garage freezer");

        ResponseEntity<DeviceResponse[]> response = restTemplate.exchange(
                "/devices?locationId=" + kitchen.id() + "&type=FRIDGE&status=ACTIVE&search=fridge&limit=1&offset=0",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "device-filter@example.com")),
                DeviceResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].id()).isEqualTo(fridge.id());
    }

    @Test
    void storesDeviceMetadataAndRejectsSecrets() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Metadata kitchen");

        ResponseEntity<DeviceResponse> created = restTemplate.exchange(
                "/devices",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", location.id().toString(),
                                "name", "Metadata fridge",
                                "type", "FRIDGE",
                                "manufacturer", "Bosch",
                                "model", "Unknown",
                                "metadata", Map.of("vendorSerial", "VC-123")),
                        headersFor(userId, "metadata@example.com")),
                DeviceResponse.class);
        ResponseEntity<String> secretMetadata = restTemplate.exchange(
                "/devices",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", location.id().toString(),
                                "name", "Secret fridge",
                                "type", "FRIDGE",
                                "metadata", Map.of("token", "do-not-store")),
                        headersFor(userId, "metadata@example.com")),
                String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().metadata()).containsEntry("vendorSerial", "VC-123");
        assertThat(secretMetadata.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(secretMetadata.getBody()).contains("metadata must not contain secrets");
    }

    @Test
    void importsAndExportsDevicesAsCsv() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Import kitchen");
        String csv = "name,type,locationId,manufacturer,model\n"
                + "Imported fridge,FRIDGE," + location.id() + ",Bosch,X1\n"
                + "Imported heater,HEATER,,Acme,H2\n";

        ResponseEntity<String> importResponse = restTemplate.exchange(
                "/devices/imports/csv",
                HttpMethod.POST,
                new HttpEntity<>(csv, textHeadersFor(userId, "import@example.com")),
                String.class);
        ResponseEntity<String> exportResponse = restTemplate.exchange(
                "/devices/export.csv",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "import@example.com")),
                String.class);
        ResponseEntity<String> invalidImport = restTemplate.exchange(
                "/devices/imports/csv",
                HttpMethod.POST,
                new HttpEntity<>("name,type\nBroken,NOPE\n", textHeadersFor(userId, "import@example.com")),
                String.class);

        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody()).contains("\"importedCount\":2");
        assertThat(exportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exportResponse.getBody()).contains("Imported fridge");
        assertThat(exportResponse.getBody()).contains("Imported heater");
        assertThat(invalidImport.getBody()).contains("\"importedCount\":0");
        assertThat(invalidImport.getBody()).contains("Unknown device type");
    }

    @Test
    void importsAndExportsDataSourcesAsCsv() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Source import lab");
        DeviceResponse device = createDevice(userId, location.id(), "Source import device");
        String csv = "externalId,sourceType,protocol\n"
                + "plug-import-001,SMART_PLUG,REST\n"
                + "mqtt-import-001,MQTT,MQTT\n";

        ResponseEntity<String> importResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/imports/csv",
                HttpMethod.POST,
                new HttpEntity<>(csv, textHeadersFor(userId, "source-import@example.com")),
                String.class);
        ResponseEntity<String> exportResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/export.csv",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "source-import@example.com")),
                String.class);
        ResponseEntity<String> invalidImport = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/imports/csv",
                HttpMethod.POST,
                new HttpEntity<>("externalId,sourceType,protocol\nbad source,NOPE,REST\n",
                        textHeadersFor(userId, "source-import@example.com")),
                String.class);

        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody()).contains("\"importedCount\":2");
        assertThat(exportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exportResponse.getBody()).contains("plug-import-001");
        assertThat(exportResponse.getBody()).contains("mqtt-import-001");
        assertThat(invalidImport.getBody()).contains("\"importedCount\":0");
        assertThat(invalidImport.getBody()).contains("unsupported characters");
    }

    @Test
    void cannotAccessForeignDevice() {
        UUID ownerId = UUID.randomUUID();
        UUID foreignUserId = UUID.randomUUID();
        LocationResponse location = createLocation(ownerId, "Owner kitchen");
        DeviceResponse device = createDevice(ownerId, location.id(), "Owner fridge");

        ResponseEntity<String> response = restTemplate.exchange(
                "/devices/" + device.id(),
                HttpMethod.GET,
                new HttpEntity<>(headersFor(foreignUserId, "foreign@example.com")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("not_found");
    }

    @Test
    void managesDeviceLifecycleAndBlocksDisabledDeviceIngestionLookup() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Lifecycle kitchen");
        DeviceResponse device = createDevice(userId, location.id(), "Lifecycle fridge");
        createDataSource(userId, device.id(), "plug-lifecycle-001");

        ResponseEntity<String> activeLookup = restTemplate.exchange(
                "/internal/devices/" + device.id() + "/data-sources/plug-lifecycle-001/exists",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);
        ResponseEntity<DeviceResponse> disabled = restTemplate.exchange(
                "/devices/" + device.id() + "/disable",
                HttpMethod.POST,
                new HttpEntity<>(headersFor(userId, "lifecycle@example.com")),
                DeviceResponse.class);
        ResponseEntity<String> disabledLookup = restTemplate.exchange(
                "/internal/devices/" + device.id() + "/data-sources/plug-lifecycle-001/exists",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);
        ResponseEntity<DeviceResponse> enabled = restTemplate.exchange(
                "/devices/" + device.id() + "/enable",
                HttpMethod.POST,
                new HttpEntity<>(headersFor(userId, "lifecycle@example.com")),
                DeviceResponse.class);
        ResponseEntity<DeviceResponse> decommissioned = restTemplate.exchange(
                "/devices/" + device.id() + "/decommission",
                HttpMethod.POST,
                new HttpEntity<>(headersFor(userId, "lifecycle@example.com")),
                DeviceResponse.class);
        ResponseEntity<String> enableAfterDecommission = restTemplate.exchange(
                "/devices/" + device.id() + "/enable",
                HttpMethod.POST,
                new HttpEntity<>(headersFor(userId, "lifecycle@example.com")),
                String.class);

        assertThat(activeLookup.getBody()).contains("\"exists\":true");
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody().status()).isEqualTo("DISABLED");
        assertThat(disabledLookup.getBody()).contains("\"exists\":false");
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enabled.getBody().status()).isEqualTo("ACTIVE");
        assertThat(decommissioned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decommissioned.getBody().status()).isEqualTo("DECOMMISSIONED");
        assertThat(enableAfterDecommission.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void publishesRegistryEventsForDeviceDomainChanges() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Event kitchen");
        DeviceResponse device = createDevice(userId, location.id(), "Event fridge");
        DataSourceResponse dataSource = createDataSource(userId, device.id(), "plug-events-001");
        clearInvocations(eventPublisher);
        clearInvocations(auditService);

        restTemplate.exchange(
                "/locations/" + location.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("name", "Event kitchen updated"), headersFor(userId, "events@example.com")),
                LocationResponse.class);
        restTemplate.exchange(
                "/devices/" + device.id() + "/disable",
                HttpMethod.POST,
                new HttpEntity<>(headersFor(userId, "events@example.com")),
                DeviceResponse.class);
        restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/" + dataSource.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(headersFor(userId, "events@example.com")),
                Void.class);
        ResponseEntity<SensorResponse> sensor = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("name", "Event sensor", "type", "TEMPERATURE", "metricMapping", "temperature"),
                        headersFor(userId, "events@example.com")),
                SensorResponse.class);
        restTemplate.exchange(
                "/devices/" + device.id() + "/analytics-config",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("enabled", false, "config", Map.of()), headersFor(userId, "events@example.com")),
                DeviceAnalyticsConfigResponse.class);

        assertThat(sensor.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(eventPublisher).publish(eq("LOCATION"), eq("UPDATED"), any(), eq(null), eq(location.id()), eq(null));
        verify(eventPublisher).publish(eq("DEVICE"), eq("STATUS_CHANGED"), any(), eq(device.id()), eq(device.id()), eq("DISABLED"));
        verify(eventPublisher).publish(eq("DATA_SOURCE"), eq("DISABLED"), any(), eq(device.id()), eq(dataSource.id()), eq("DISABLED"));
        verify(eventPublisher).publish(eq("SENSOR"), eq("CREATED"), any(), eq(device.id()), eq(sensor.getBody().id()), eq(null));
        verify(eventPublisher).publish(eq("ANALYTICS_CONFIG"), eq("UPDATED"), any(), eq(device.id()), any(), eq("DISABLED"));
        verify(auditService).record(any(), any(), eq("LOCATION_UPDATED"), eq("LOCATION"), eq(location.id()));
        verify(auditService).record(any(), any(), eq("DEVICE_STATUS_CHANGED"), eq("DEVICE"), eq(device.id()));
        verify(auditService).record(any(), any(), eq("DATA_SOURCE_DISABLED"), eq("DATA_SOURCE"), eq(dataSource.id()));
        verify(auditService).record(any(), any(), eq("SENSOR_CREATED"), eq("SENSOR"), eq(sensor.getBody().id()));
        verify(auditService).record(any(), any(), eq("ANALYTICS_CONFIG_UPDATED"), eq("ANALYTICS_CONFIG"), any());
    }

    @Test
    void createsDataSource() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Kitchen");
        DeviceResponse device = createDevice(userId, location.id(), "Kitchen fridge");

        ResponseEntity<DataSourceResponse> response = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("sourceType", "SMART_PLUG", "protocol", "REST", "externalId", "plug-001"),
                        headersFor(userId, "datasource@example.com")),
                DataSourceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deviceId()).isEqualTo(device.id());
        assertThat(response.getBody().externalId()).isEqualTo("plug-001");
    }

    @Test
    void validatesDataSourceSourceIdFormat() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Validation kitchen");
        DeviceResponse device = createDevice(userId, location.id(), "Validation fridge");

        ResponseEntity<String> response = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("sourceType", "SMART_PLUG", "protocol", "REST", "externalId", "bad source id"),
                        headersFor(userId, "validation@example.com")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation_failed");
    }

    @Test
    void listsGetsUpdatesAndDisablesDataSourceForOwnedDeviceOnly() {
        UUID ownerId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        LocationResponse location = createLocation(ownerId, "Source kitchen");
        DeviceResponse device = createDevice(ownerId, location.id(), "Source fridge");
        DataSourceResponse dataSource = createDataSource(ownerId, device.id(), "plug-api-001");

        ResponseEntity<DataSourceResponse[]> listResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(ownerId, "owner-source@example.com")),
                DataSourceResponse[].class);
        ResponseEntity<DataSourceResponse> getResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/" + dataSource.id(),
                HttpMethod.GET,
                new HttpEntity<>(headersFor(ownerId, "owner-source@example.com")),
                DataSourceResponse.class);
        ResponseEntity<DataSourceResponse> updateResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/" + dataSource.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of("sourceType", "SENSOR", "protocol", "MQTT", "externalId", "plug-api-002"),
                        headersFor(ownerId, "owner-source@example.com")),
                DataSourceResponse.class);
        ResponseEntity<String> foreignGet = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/" + dataSource.id(),
                HttpMethod.GET,
                new HttpEntity<>(headersFor(foreignId, "foreign-source@example.com")),
                String.class);
        ResponseEntity<Void> disableResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/" + dataSource.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(headersFor(ownerId, "owner-source@example.com")),
                Void.class);
        ResponseEntity<String> internalResponse = restTemplate.exchange(
                "/internal/data-sources/" + dataSource.id(),
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).extracting(DataSourceResponse::id).contains(dataSource.id());
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().externalId()).isEqualTo("plug-api-001");
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().sourceType()).isEqualTo("SENSOR");
        assertThat(updateResponse.getBody().protocol()).isEqualTo("MQTT");
        assertThat(updateResponse.getBody().externalId()).isEqualTo("plug-api-002");
        assertThat(foreignGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(internalResponse.getBody()).contains("\"status\":\"DISABLED\"");
    }

    @Test
    void internalDataSourceEndpointsRequireServiceTokenAndExposeTokenHash() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Kitchen");
        DeviceResponse device = createDevice(userId, location.id(), "Kitchen fridge");
        DataSourceResponse dataSource = createDataSource(userId, device.id(), "plug-secure-001");

        ResponseEntity<SourceTokenResponse> tokenResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/data-sources/" + dataSource.id() + "/token",
                HttpMethod.POST,
                new HttpEntity<>(headersFor(userId, "datasource-token@example.com")),
                SourceTokenResponse.class);
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResponse.getBody()).isNotNull();

        ResponseEntity<String> unauthorized = restTemplate.getForEntity(
                "/internal/devices/{deviceId}/data-sources/{sourceId}/exists",
                String.class,
                device.id(),
                "plug-secure-001");
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> exists = restTemplate.exchange(
                "/internal/devices/" + device.id() + "/data-sources/plug-secure-001/exists",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);
        assertThat(exists.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exists.getBody()).contains("\"exists\":true");

        ResponseEntity<String> byTokenPrefix = restTemplate.exchange(
                "/internal/data-sources/by-token-prefix/" + tokenResponse.getBody().tokenPrefix(),
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);
        assertThat(byTokenPrefix.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byTokenPrefix.getBody()).contains("\"tokenHash\"");
        assertThat(byTokenPrefix.getBody()).doesNotContain(tokenResponse.getBody().token());
    }

    @Test
    void internalOwnershipCheckReturnsAccessDecision() {
        UUID ownerId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        LocationResponse location = createLocation(ownerId, "Owner kitchen");
        DeviceResponse device = createDevice(ownerId, location.id(), "Owner fridge");

        ResponseEntity<String> ownerAccess = restTemplate.exchange(
                "/internal/devices/" + device.id() + "/access?userId=" + ownerId,
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);
        ResponseEntity<String> foreignAccess = restTemplate.exchange(
                "/internal/devices/" + device.id() + "/access?userId=" + foreignId,
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                String.class);

        assertThat(ownerAccess.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerAccess.getBody()).contains("\"allowed\":true");
        assertThat(foreignAccess.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(foreignAccess.getBody()).contains("\"allowed\":false");
    }

    @Test
    void readsAndUpdatesAnalyticsConfigForOwnedDevice() {
        UUID userId = UUID.randomUUID();
        LocationResponse location = createLocation(userId, "Analytics kitchen");
        DeviceResponse device = createDevice(userId, location.id(), "Analytics fridge");

        ResponseEntity<DeviceAnalyticsConfigResponse> defaultResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/analytics-config",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(userId, "analytics@example.com")),
                DeviceAnalyticsConfigResponse.class);
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/analytics-config",
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of("enabled", false, "config", Map.of("baselineWindowDays", 14)),
                        headersFor(userId, "analytics@example.com")),
                String.class);

        assertThat(defaultResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(defaultResponse.getBody()).isNotNull();
        assertThat(defaultResponse.getBody().enabled()).isTrue();
        assertThat(updateResponse.getStatusCode()).as(updateResponse.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).contains("\"enabled\":false");
        assertThat(updateResponse.getBody()).contains("baselineWindowDays");
    }

    @Test
    void managesSensorsForOwnedDeviceOnly() {
        UUID ownerId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        LocationResponse location = createLocation(ownerId, "Sensor kitchen");
        DeviceResponse device = createDevice(ownerId, location.id(), "Sensor fridge");

        ResponseEntity<SensorResponse> createResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "name", "Compressor temperature",
                                "type", "TEMPERATURE",
                                "unit", "C",
                                "metricMapping", "temperature",
                                "metadata", Map.of("placement", "compressor")),
                        headersFor(ownerId, "sensor-owner@example.com")),
                SensorResponse.class);
        SensorResponse created = createResponse.getBody();
        ResponseEntity<SensorResponse[]> listResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(ownerId, "sensor-owner@example.com")),
                SensorResponse[].class);
        ResponseEntity<SensorResponse> updateResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors/" + created.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of(
                                "name", "Door switch",
                                "type", "CONTACT",
                                "unit", "state",
                                "metricMapping", "door_open",
                                "metadata", Map.of("normallyOpen", true)),
                        headersFor(ownerId, "sensor-owner@example.com")),
                SensorResponse.class);
        ResponseEntity<String> foreignGet = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors/" + created.id(),
                HttpMethod.GET,
                new HttpEntity<>(headersFor(foreignId, "sensor-foreign@example.com")),
                String.class);
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors/" + created.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(headersFor(ownerId, "sensor-owner@example.com")),
                Void.class);
        ResponseEntity<String> getAfterDelete = restTemplate.exchange(
                "/devices/" + device.id() + "/sensors/" + created.id(),
                HttpMethod.GET,
                new HttpEntity<>(headersFor(ownerId, "sensor-owner@example.com")),
                String.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created).isNotNull();
        assertThat(created.metricMapping()).isEqualTo("temperature");
        assertThat(created.metadata()).containsEntry("placement", "compressor");
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).extracting(SensorResponse::id).contains(created.id());
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().name()).isEqualTo("Door switch");
        assertThat(updateResponse.getBody().metricMapping()).isEqualTo("door_open");
        assertThat(foreignGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private LocationResponse createLocation(UUID userId, String name) {
        ResponseEntity<LocationResponse> response = restTemplate.exchange(
                "/locations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "Test location"), headersFor(userId, "user@example.com")),
                LocationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private DeviceResponse createDevice(UUID userId, UUID locationId, String name) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/devices",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", locationId.toString(),
                                "name", name,
                                "type", "FRIDGE",
                                "manufacturer", "Bosch",
                                "model", "Unknown"),
                        headersFor(userId, "user@example.com")),
                String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readValue(response.getBody(), DeviceResponse.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse device response", exception);
        }
    }

    private DataSourceResponse createDataSource(UUID userId, UUID deviceId, String sourceId) {
        ResponseEntity<DataSourceResponse> response = restTemplate.exchange(
                "/devices/" + deviceId + "/data-sources",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("sourceType", "SMART_PLUG", "protocol", "REST", "externalId", sourceId),
                        headersFor(userId, "datasource@example.com")),
                DataSourceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders headersFor(UUID userId, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-User-Email", email);
        headers.set("X-User-Role", "USER");
        return headers;
    }

    private HttpHeaders textHeadersFor(UUID userId, String email) {
        HttpHeaders headers = headersFor(userId, email);
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        return headers;
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Service-Token", "dev-internal-token");
        return headers;
    }

}
