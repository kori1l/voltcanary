package com.voltcanary.simulator.sender;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.simulator")
public class SimulatorSenderProperties {
    private Mqtt mqtt = new Mqtt();
    private Http http = new Http();

    public Mqtt getMqtt() { return mqtt; }
    public void setMqtt(Mqtt mqtt) { this.mqtt = mqtt; }
    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }

    public static class Mqtt {
        private String brokerUrl = "tcp://localhost:1883";
        private String topicTemplate = "voltcanary/sources/{sourceId}/telemetry";
        public String getBrokerUrl() { return brokerUrl; }
        public void setBrokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; }
        public String getTopicTemplate() { return topicTemplate; }
        public void setTopicTemplate(String topicTemplate) { this.topicTemplate = topicTemplate; }
    }

    public static class Http {
        private String ingestionUrl = "http://localhost:8083/ingestion/readings";
        public String getIngestionUrl() { return ingestionUrl; }
        public void setIngestionUrl(String ingestionUrl) { this.ingestionUrl = ingestionUrl; }
    }
}
