package com.voltcanary.device.event;

import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import com.voltcanary.device.service.IdentityAccountLifecycleEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.consume-enabled", havingValue = "true")
public class IdentityAccountLifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdentityAccountLifecycleConsumer.class);

    private final IdentityAccountLifecycleEventHandler eventHandler;

    public IdentityAccountLifecycleConsumer(IdentityAccountLifecycleEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

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
