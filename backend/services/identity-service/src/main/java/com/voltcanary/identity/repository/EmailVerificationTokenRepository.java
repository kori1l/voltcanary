package com.voltcanary.identity.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import com.voltcanary.identity.domain.EmailVerificationToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from EmailVerificationToken token where token.expiresAt < :cutoff")
    int deleteExpired(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
}
