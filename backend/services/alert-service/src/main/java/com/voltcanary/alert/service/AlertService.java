package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.dto.AlertListFilters;
import com.voltcanary.alert.dto.AlertPageResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.dto.AlertSummaryResponse;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

/** Network authorization precedes short local read/write transactions. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class AlertService {
    private final AlertAccessService access;
    private final DeviceHistoryDeletion deletions;
    private final AlertCreationWrites creation;
    private final AlertLifecycleWrites lifecycle;
    private final AlertReadQueries reads;

    public Alert handleAnomaly(AnomalyDetectedEvent event) {
        if (!deletions.writable(event.deviceId())) return null;
        return creation.handleAnomaly(event, access.resolveDeviceAccountId(event.deviceId(), "anomaly:" + event.eventId()));
    }
    public Alert handleRiskScoreChanged(RiskScoreChangedEvent event) {
        if (!deletions.writable(event.deviceId())) return null;
        return creation.handleRiskScoreChanged(event, access.resolveDeviceAccountId(event.deviceId(), "risk-score:" + event.eventId()));
    }
    public List<AlertResponse> listAlerts(String userId, String role, String correlationId, AlertListFilters filters) {
        return reads.list(access.accountScope(userId), normalized(filters));
    }
    public AlertSummaryResponse alertSummary(String userId, String role, String correlationId, AlertListFilters filters) {
        return reads.summary(access.accountScope(userId), normalized(filters));
    }
    public AlertPageResponse pageAlerts(String userId, String role, String correlationId, AlertListFilters filters, Integer page, Integer size) {
        return reads.page(access.accountScope(userId), normalized(filters), normalizedPage(page), normalizedSize(size));
    }
    public AlertPageResponse supportSearchAlerts(String userId, String role, AlertListFilters filters, UUID accountId, Integer page, Integer size) {
        access.requirePrivileged(userId, role);
        UUID own = access.ownAccount(userId);
        if (accountId != null && !own.equals(accountId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Explicit resource scope required");
        return reads.page(own, normalized(filters), normalizedPage(page), normalizedSize(size));
    }
    public AlertResponse getAlert(UUID id, String userId, String role, String correlationId) {
        return AlertViews.response(authorized(id, userId, role, correlationId));
    }
    public AlertResponse acknowledge(UUID id, String userId, String role, String correlationId) {
        authorized(id, userId, role, correlationId);
        return lifecycle.acknowledge(id, parseUserId(userId), correlationId);
    }
    public AlertResponse resolve(UUID id, String reason, String userId, String role, String correlationId) {
        authorized(id, userId, role, correlationId);
        return lifecycle.resolve(id, reason, parseUserId(userId), correlationId);
    }
    public AlertResponse workspaceAcknowledge(UUID id, String authorization, UUID workspace, String userId, String correlationId) {
        var alert = reads.snapshot(id);
        var actor = parseUserId(userId);
        access.workspaceAction(authorization, workspace, alert.getAccountId(), actor, alert.getDeviceId(), "incident.ack");
        return lifecycle.acknowledge(id, actor, correlationId);
    }
    public AlertResponse workspaceResolve(UUID id, String reason, String authorization, UUID workspace, String userId, String correlationId) {
        var alert = reads.snapshot(id);
        var actor = parseUserId(userId);
        access.workspaceAction(authorization, workspace, alert.getAccountId(), actor, alert.getDeviceId(), "incident.resolve");
        return lifecycle.resolve(id, reason, actor, correlationId);
    }
    private Alert authorized(UUID id, String userId, String role, String correlationId) {
        var alert = reads.snapshot(id);
        access.requireReadAccess(alert.getDeviceId(), userId, role, correlationId);
        return alert;
    }
    private AlertListFilters normalized(AlertListFilters filters) {
        return filters == null ? new AlertListFilters(null, null, null, null, null, null, null, null) : filters;
    }
    private int normalizedPage(Integer page) {
        return page == null ? 0 : Math.max(0, page);
    }
    private int normalizedSize(Integer size) {
        if (size == null) {
            return 50;
        }
        return Math.max(1, Math.min(size, 200));
    }
    private UUID parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
