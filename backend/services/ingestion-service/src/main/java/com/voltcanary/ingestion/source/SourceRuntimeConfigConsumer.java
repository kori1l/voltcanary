package com.voltcanary.ingestion.source;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class SourceRuntimeConfigConsumer {

    public static final String TOPIC = "source.runtime-config.v1";
    private static final Logger log = LoggerFactory.getLogger(SourceRuntimeConfigConsumer.class);

    private final SourceRegistryCache cache;

    public SourceRuntimeConfigConsumer(SourceRegistryCache cache) {
        this.cache = cache;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = "${spring.application.name:ingestion-service}",
            containerFactory = "sourceRuntimeConfigKafkaListenerContainerFactory")
    public void consume(
            SourceRuntimeConfigEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        cache.apply(event);
        log.debug(
                "sourceRuntimeConfigApplied key={} dataSourceId={} sourceId={} status={} version={}",
                key,
                event.dataSourceId(),
                event.sourceId(),
                event.status(),
                event.configVersion());
    }
}
