package com.voltcanary.aiagent.audit;

import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j(topic = "ai-agent-audit")
public class AuditLogger {

    public void record(
        AuthenticatedUser user,
        String eventType,
        UUID planId,
        UUID actionId,
        Map<String, Object> details
    ) {
        log.info(
            "eventType={} userId={} email={} role={} planId={} actionId={} details={}",
            eventType,
            user == null ? null : user.userId(),
            user == null ? null : user.email(),
            user == null ? null : user.role(),
            planId,
            actionId,
            details
        );
    }
}
