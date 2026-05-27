package com.voltcanary.aiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "server.port=0",
        "app.services.device-base-url=http://localhost:8082",
        "app.services.simulator-base-url=http://localhost:8087"
})
class AiAgentServiceSmokeTest {
    @Test
    void contextLoads() {
    }
}
