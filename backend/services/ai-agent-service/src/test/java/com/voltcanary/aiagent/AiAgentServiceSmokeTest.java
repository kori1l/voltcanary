package com.voltcanary.aiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "server.port=0",
        "app.services.device-base-url=http://localhost:8082",
        "app.services.alert-base-url=http://localhost:8084",
        "app.services.simulator-base-url=http://localhost:8087",
        "app.ai.services.device-base-url=http://localhost:8082",
        "app.ai.services.alert-base-url=http://localhost:8084",
        "app.ai.services.simulator-base-url=http://localhost:8087",
        "spring.datasource.url=jdbc:h2:mem:aiagent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "app.ai.secrets.encryption-key=change_me_32_bytes_minimum_local_dev_key"
})
class AiAgentServiceSmokeTest {
    @Test
    void contextLoads() {
    }
}
