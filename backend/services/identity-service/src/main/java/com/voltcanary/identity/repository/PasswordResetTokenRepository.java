package com.voltcanary.identity.repository;

import com.voltcanary.identity.domain.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
        "delete from PasswordResetToken token where token.expiresAt < :cutoff"
    )
    int deleteExpired(
        @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff
    );
}
