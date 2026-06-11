package com.voltcanary.device.service;

import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import com.voltcanary.device.domain.AccountStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityAccountLifecycleEventHandler {

    private static final String SOURCE_NAME = "identity.account-lifecycle.v1";

    private final ProcessedEventService processedEventService;
    private final AccountService accountService;

    public IdentityAccountLifecycleEventHandler(
            ProcessedEventService processedEventService,
            AccountService accountService) {
        this.processedEventService = processedEventService;
        this.accountService = accountService;
    }

    @Transactional
    public boolean handle(IdentityAccountLifecycleEvent event) {
        if (!processedEventService.markIfNew(SOURCE_NAME, event.eventId())) {
            return false;
        }
        accountService.syncUser(event.userId(), event.email(), AccountStatus.valueOf(event.status()));
        return true;
    }
}
