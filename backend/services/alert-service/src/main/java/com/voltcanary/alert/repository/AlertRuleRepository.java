package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.AlertRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID>, JpaSpecificationExecutor<AlertRule> {

    List<AlertRule> findByAccountIdOrderByUpdatedAtDesc(UUID accountId);

    Optional<AlertRule> findByIdAndAccountId(UUID id, UUID accountId);
}
