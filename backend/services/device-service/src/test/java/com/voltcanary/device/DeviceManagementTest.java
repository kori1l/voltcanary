package com.voltcanary.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.account.AccountResponse;
import com.voltcanary.device.datasource.DataSourceResponse;
import com.voltcanary.device.device.DeviceResponse;
import com.voltcanary.device.location.LocationResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "JWT_SECRET=change_me"
        })
class DeviceManagementTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

    private HttpHeaders headersFor(UUID userId, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt(userId, email));
        return headers;
    }

    private String jwt(UUID userId, String email) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .claim("email", email)
                    .claim("role", "USER")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(900)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(sha256("change_me")));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create test JWT", exception);
        }
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
