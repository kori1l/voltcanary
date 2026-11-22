package com.voltcanary.device.service;

import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import com.voltcanary.device.domain.AccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdentityAccountLifecycleEventHandler {

    private static final String SOURCE_NAME = "identity.account-lifecycle.v1";

    private final ProcessedEventService processedEventService;
    private final AccountService accountService;

    @Transactional
    public boolean handle(IdentityAccountLifecycleEvent event) {
        if (!processedEventService.markIfNew(SOURCE_NAME, event.eventId())) {
            return false;
        }
        accountService.syncUser(
            event.userId(),
            event.email(),
            AccountStatus.valueOf(event.status())
        );
        return true;
    }
}
