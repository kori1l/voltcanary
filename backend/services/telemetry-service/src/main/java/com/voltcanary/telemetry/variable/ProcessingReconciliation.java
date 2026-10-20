package com.voltcanary.telemetry.variable;

import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.telemetry.config.VariableProcessingProperties;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Component
public class ProcessingReconciliation {
    public record Entry(long cursor, SourceProcessingConfigEvent bundle) { }
    private final ProcessingBundles bundles;
    private final VariableProcessingProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RestClient client;

    public ProcessingReconciliation(ProcessingBundles bundles, VariableProcessingProperties properties,
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager, RestClient.Builder builder) {
        this.bundles = bundles;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl(properties.getDeviceServiceBaseUrl()).requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    public void reconcile() {
        if (!properties.isEnabled()) return;
        try {
            transaction.executeWithoutResult(status -> {
                Long cursor = jdbc.queryForObject("select cursor_value from variable_processing_cursor where id=1 for update", Long.class);
                Entry[] page = client.get().uri("/internal/source-processing-bundles?after={after}&limit=100", cursor)
                        .header("X-Internal-Service-Token", properties.getInternalServiceToken())
                        .retrieve().body(Entry[].class);
                if (page == null) return;
                for (Entry entry : page) {
                    bundles.apply(entry.bundle());
                    jdbc.update("update variable_processing_cursor set cursor_value=? where id=1", entry.cursor());
                }
            });
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Processing reconciliation unavailable cause={}",
                    failure.getClass().getSimpleName());
        }
    }
}
