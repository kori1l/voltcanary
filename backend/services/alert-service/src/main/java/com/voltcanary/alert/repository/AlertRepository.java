package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.Alert;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    List<Alert> findTop100ByOrderByCreatedAtDesc();

    Optional<Alert> findFirstByDeduplicationKeyOrderByCreatedAtDesc(String deduplicationKey);
}
