package com.voltcanary.device.service;

import com.voltcanary.device.dto.DashboardScopeRequest;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.DashboardScopeReadService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class WorkspaceSourceReads {

    public record Source(
        UUID id,
        UUID deviceId,
        String sourceType,
        String protocol,
        String externalId
    ) {}

    private final AuthenticatedUserResolver users;
    private final DashboardScopeReadService scopes;
    private final DataSourceRepository sources;

    public List<Source> list(String authorization, UUID workspace, UUID device, UUID account) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        var scope = scopes.read(
            users.resolve(headers),
            new DashboardScopeRequest(
                "FIXED",
                Set.of(device),
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                workspace,
                account,
                "device.read"
            )
        );
        if (
            scope
                .devices()
                .stream()
                .noneMatch(d -> d.id().equals(device))
        ) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Current device capability unavailable"
        );
        return sources
            .findByDeviceId(device)
            .stream()
            .limit(100)
            .map(s ->
                new Source(
                    s.getId(),
                    device,
                    s.getSourceType().name(),
                    s.getProtocol().name(),
                    s.getSourceId()
                )
            )
            .toList();
    }
}
