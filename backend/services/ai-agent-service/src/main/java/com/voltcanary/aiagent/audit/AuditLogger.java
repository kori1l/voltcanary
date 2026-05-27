package com.voltcanary.aiagent.audit;

import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("ai-agent-audit");

    public void record(AuthenticatedUser user, String eventType, UUID planId, UUID actionId, Map<String, Object> details) {
        AUDIT.info(
                "eventType={} userId={} email={} role={} planId={} actionId={} details={}",
                eventType,
                user == null ? null : user.userId(),
                user == null ? null : user.email(),
                user == null ? null : user.role(),
                planId,
                actionId,
                details);
    }
}
