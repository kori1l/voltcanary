package com.voltcanary.ingestion.source;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SourceRuntimeConfigConsumer {

    public static final String TOPIC = "source.runtime-config.v1";

    private final SourceRegistryCache cache;
    private final SourceRuntimeConfigStore runtimeConfigStore;

    @KafkaListener(
            topics = TOPIC,
            groupId = "#{@sourceRuntimeConfigConsumerGroupId}",
            containerFactory = "sourceRuntimeConfigKafkaListenerContainerFactory")
    public void consume(
            SourceRuntimeConfigEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        cache.apply(event);
        runtimeConfigStore.apply(event);
        log.debug(
                "sourceRuntimeConfigApplied key={} dataSourceId={} sourceId={} status={} version={}",
                key,
                event.dataSourceId(),
                event.sourceId(),
                event.status(),
                event.configVersion());
    }
}
