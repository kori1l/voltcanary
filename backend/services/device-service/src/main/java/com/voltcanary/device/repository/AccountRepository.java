package com.voltcanary.device.repository;

import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserId(UUID userId);
}
