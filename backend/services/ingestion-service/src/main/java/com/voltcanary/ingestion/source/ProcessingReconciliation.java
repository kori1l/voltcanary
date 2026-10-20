package com.voltcanary.ingestion.source;

import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.ingestion.config.VariableProcessingProperties;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProcessingReconciliation {
    public record Entry(long cursor, SourceProcessingConfigEvent bundle) { }
    private final SourceProcessingProjection projection;
    private final VariableProcessingProperties properties;
    private final RestClient client;
    private long cursor;

    public ProcessingReconciliation(SourceProcessingProjection projection,
            VariableProcessingProperties properties, RestClient.Builder builder) {
        this.projection = projection;
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl(properties.getDeviceServiceBaseUrl()).requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    public synchronized void reconcile() {
        if (!properties.isEnabled()) return;
        try {
            Entry[] page = client.get().uri("/internal/source-processing-bundles?after={after}&limit=100", cursor)
                    .header("X-Internal-Service-Token", properties.getInternalServiceToken())
                    .retrieve().body(Entry[].class);
            if (page == null) return;
            for (Entry entry : page) {
                projection.apply(entry.bundle());
                cursor = entry.cursor();
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Processing reconciliation unavailable cause={}",
                    failure.getClass().getSimpleName());
        }
    }
}
