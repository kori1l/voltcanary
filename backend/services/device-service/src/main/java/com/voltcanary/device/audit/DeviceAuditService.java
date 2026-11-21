package com.voltcanary.device.audit;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.security.AuthenticatedUser;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@Slf4j
public class DeviceAuditService {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    public void record(AuthenticatedUser user, UUID accountId, String action, String targetType, UUID targetId) {
        log.info(
                "deviceAudit action={} targetType={} targetId={} userId={} accountId={} correlationId={}",
                action,
                targetType,
                targetId,
                user == null ? null : user.id(),
                accountId,
                correlationId());
    }

    private String correlationId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest().getHeader(CORRELATION_ID_HEADER);
    }
}
