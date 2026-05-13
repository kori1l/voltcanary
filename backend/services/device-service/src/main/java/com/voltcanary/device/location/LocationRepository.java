package com.voltcanary.device.location;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<Location> findByIdAndAccountId(UUID id, UUID accountId);
}
