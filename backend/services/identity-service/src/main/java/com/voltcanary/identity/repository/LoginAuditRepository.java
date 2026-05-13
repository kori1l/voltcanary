package com.voltcanary.identity.repository;

import com.voltcanary.identity.domain.LoginAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAuditRepository extends JpaRepository<LoginAudit, UUID> {
}
