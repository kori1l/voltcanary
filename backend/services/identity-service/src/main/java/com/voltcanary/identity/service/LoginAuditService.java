package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.LoginAudit;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.repository.LoginAuditRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginAuditService {

    private final LoginAuditRepository loginAuditRepository;

    public LoginAuditService(LoginAuditRepository loginAuditRepository) {
        this.loginAuditRepository = loginAuditRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            User user,
            String email,
            boolean successful,
            String failureReason,
            String ipAddress,
            String userAgent,
            String correlationId) {
        LoginAudit audit = new LoginAudit();
        audit.setId(UUID.randomUUID());
        audit.setUser(user);
        audit.setEmail(email);
        audit.setSuccessful(successful);
        audit.setFailureReason(failureReason);
        audit.setFailureCategory(normalizeFailureCategory(failureReason));
        audit.setIpAddress(blankToNull(ipAddress));
        audit.setUserAgent(truncate(blankToNull(userAgent), 512));
        audit.setCorrelationId(blankToNull(correlationId));
        audit.setCreatedAt(Instant.now());
        loginAuditRepository.save(audit);
    }

    private String normalizeFailureCategory(String failureReason) {
        return failureReason == null ? null : failureReason.trim().toLowerCase().replace('-', '_');
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
