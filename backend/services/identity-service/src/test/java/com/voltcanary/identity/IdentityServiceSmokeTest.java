package com.voltcanary.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none" }
)
class IdentityServiceSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {}

    @Test
    void healthEndpointReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/actuator/health",
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void internalPingReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/internal/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
