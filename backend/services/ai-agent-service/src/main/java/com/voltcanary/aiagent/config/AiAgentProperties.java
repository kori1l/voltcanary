package com.voltcanary.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiAgentProperties {
    private String provider = "rules";
    private OpenAi openai = new OpenAi();
    private Services services = new Services();
    private Secrets secrets = new Secrets();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAi openai) {
        this.openai = openai;
    }

    public Services getServices() {
        return services;
    }

    public void setServices(Services services) {
        this.services = services;
    }

    public Secrets getSecrets() {
        return secrets;
    }

    public void setSecrets(Secrets secrets) {
        this.secrets = secrets;
    }

    public static class OpenAi {
        private String apiKey = "";
        private String model = "gpt-5.4-mini";
        private String baseUrl = "https://api.openai.com/v1";
        private int timeoutMs = 30000;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Services {
        private String deviceBaseUrl = "http://localhost:8082";
        private String alertBaseUrl = "http://localhost:8084";
        private String simulatorBaseUrl = "http://localhost:8087";

        public String getDeviceBaseUrl() { return deviceBaseUrl; }
        public void setDeviceBaseUrl(String deviceBaseUrl) { this.deviceBaseUrl = deviceBaseUrl; }
        public String getAlertBaseUrl() { return alertBaseUrl; }
        public void setAlertBaseUrl(String alertBaseUrl) { this.alertBaseUrl = alertBaseUrl; }
        public String getSimulatorBaseUrl() { return simulatorBaseUrl; }
        public void setSimulatorBaseUrl(String simulatorBaseUrl) { this.simulatorBaseUrl = simulatorBaseUrl; }
    }

    public static class Secrets {
        private String encryptionKey = "";
        private String keyVersion = "v1";

        public String getEncryptionKey() { return encryptionKey; }
        public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
        public String getKeyVersion() { return keyVersion; }
        public void setKeyVersion(String keyVersion) { this.keyVersion = keyVersion; }
    }
}
