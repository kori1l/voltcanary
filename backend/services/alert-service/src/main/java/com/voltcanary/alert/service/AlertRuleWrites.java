package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.alert.repository.AlertRuleRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Only local persistence follows remote validation; the locked version must still match. */
@Service
@RequiredArgsConstructor
public class AlertRuleWrites {

    private final AlertRuleRepository repository;
    private final EntityManager entityManager;

    @Transactional
    public AlertRule create(AlertRule validated) {
        return repository.save(validated);
    }

    @Transactional
    public AlertRule update(AlertRule validated, int expectedVersion) {
        requireVersion(validated.getId(), validated.getAccountId(), expectedVersion);
        return repository.save(validated);
    }

    @Transactional
    public void delete(AlertRule validated) {
        var actual = requireVersion(
            validated.getId(),
            validated.getAccountId(),
            validated.getVersion()
        );
        repository.delete(actual);
    }

    private AlertRule requireVersion(UUID id, UUID account, int version) {
        var actual = repository
            .lockOwned(id, account)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert rule not found")
            );
        entityManager.refresh(actual);
        if (actual.getVersion() != version) throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Alert rule changed; reload"
        );
        return actual;
    }
}
