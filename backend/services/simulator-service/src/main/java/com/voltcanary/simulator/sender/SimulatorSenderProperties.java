package com.voltcanary.simulator.sender;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.simulator")
@Getter
@Setter
public class SimulatorSenderProperties {

    private Mqtt mqtt = new Mqtt();
    private Http http = new Http();

    public static class Mqtt {

        @Getter
        @Setter
        private String brokerUrl = "tcp://localhost:1883";

        @Getter
        @Setter
        private String topicTemplate = "voltcanary/sources/{sourceId}/telemetry";
    }

    public static class Http {

        @Getter
        @Setter
        private String ingestionUrl = "http://localhost:8083/ingestion/readings";
    }
}
