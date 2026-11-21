package com.voltcanary.device.event;

import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import com.voltcanary.device.service.IdentityAccountLifecycleEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.consume-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IdentityAccountLifecycleConsumer {

    private final IdentityAccountLifecycleEventHandler eventHandler;

    @KafkaListener(
            topics = "identity.account-lifecycle.v1",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "identityAccountLifecycleKafkaListenerContainerFactory")
    public void consume(IdentityAccountLifecycleEvent event) {
        boolean processed = eventHandler.handle(event);
        log.info(
                "identityAccountLifecycleConsumed eventId={} userId={} status={} processed={}",
                event.eventId(),
                event.userId(),
                event.status(),
                processed);
    }
}
