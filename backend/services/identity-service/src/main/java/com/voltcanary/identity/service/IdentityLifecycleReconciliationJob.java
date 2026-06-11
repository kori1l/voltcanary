package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityDownstreamProperties;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.scheduling.SchedulerLockService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdentityLifecycleReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(IdentityLifecycleReconciliationJob.class);
    private static final String LOCK_NAME = "identity-device-lifecycle-reconciliation";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final IdentityDownstreamProperties properties;
    private final UserRepository userRepository;
    private final DeviceAccountLifecycleClient deviceAccountLifecycleClient;
    private final IdentityLifecycleOutboxService identityLifecycleOutboxService;
    private final SchedulerLockService schedulerLockService;

    public IdentityLifecycleReconciliationJob(
            IdentityDownstreamProperties properties,
            UserRepository userRepository,
            DeviceAccountLifecycleClient deviceAccountLifecycleClient,
            IdentityLifecycleOutboxService identityLifecycleOutboxService,
            SchedulerLockService schedulerLockService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.deviceAccountLifecycleClient = deviceAccountLifecycleClient;
        this.identityLifecycleOutboxService = identityLifecycleOutboxService;
        this.schedulerLockService = schedulerLockService;
    }

    @Scheduled(fixedDelayString = "${app.identity.lifecycle-reconciliation.delay:5m}")
    public void reconcileDeviceLifecycle() {
        if (!properties.isDeviceLifecycleReconciliationEnabled()) {
            return;
        }
        if (!schedulerLockService.acquire(LOCK_NAME, LOCK_TTL)) {
            return;
        }
        try {
            reconcileBatch();
        } finally {
            schedulerLockService.release(LOCK_NAME);
        }
    }

    private void reconcileBatch() {
        int batchSize = Math.max(1, properties.getDeviceLifecycleReconciliationBatchSize());
        List<User> users = userRepository.findAll(PageRequest.of(
                0,
                batchSize,
                Sort.by(Sort.Direction.DESC, "updatedAt"))).getContent();
        int repaired = 0;
        for (User user : users) {
            String expected = accountStatus(user.getStatus());
            Optional<String> actual = deviceAccountLifecycleClient.findAccountStatus(user.getId());
            boolean missingInactiveProjection = actual.isEmpty() && user.getStatus() != UserStatus.ACTIVE;
            boolean mismatchedProjection = actual.isPresent() && !expected.equals(actual.get());
            if (missingInactiveProjection || mismatchedProjection) {
                identityLifecycleOutboxService.enqueueAccountLifecycle(user);
                repaired++;
                log.info(
                        "identityLifecycleDriftRepaired userId={} expectedStatus={} actualStatus={}",
                        user.getId(),
                        expected,
                        actual.orElse("MISSING"));
            }
        }
        log.info("identityLifecycleReconciliationCompleted checked={} repaired={}", users.size(), repaired);
    }

    private String accountStatus(UserStatus userStatus) {
        if (userStatus == UserStatus.ACTIVE) {
            return "ACTIVE";
        }
        if (userStatus == UserStatus.DELETED) {
            return "DELETED";
        }
        return "DISABLED";
    }
}
