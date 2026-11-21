package com.voltcanary.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai")
@Getter
@Setter
public class AiAgentProperties {
    private boolean deviceSetupEnabled;

    private boolean dashboardOperationsEnabled;

    private String provider = "rules";
    private OpenAi openai = new OpenAi();
    private Services services = new Services();
    private Secrets secrets = new Secrets();

    public static class OpenAi {
        @Getter
        @Setter
        private String apiKey = "";
        @Getter
        @Setter
        private String model = "gpt-5.4-mini";
        @Getter
        @Setter
        private String baseUrl = "https://api.openai.com/v1";
        @Getter
        @Setter
        private int timeoutMs = 30000;

    }

    public static class Services {
        @Getter
        @Setter
        private String hslConnectorBaseUrl = "http://hsl-connector:8091";

        @Getter
        @Setter
        private String deviceBaseUrl = "http://localhost:8082";
        @Getter
        @Setter
        private String alertBaseUrl = "http://localhost:8084";
        @Getter
        @Setter
        private String simulatorBaseUrl = "http://localhost:8087";
        @Getter
        @Setter
        private String dashboardBaseUrl = "http://localhost:8089";
        @Getter
        @Setter
        private String telemetryBaseUrl = "http://localhost:8084";

    }

    public static class Secrets {
        @Getter
        @Setter
        private String encryptionKey = "";
        @Getter
        @Setter
        private String keyVersion = "v1";

    }
}
