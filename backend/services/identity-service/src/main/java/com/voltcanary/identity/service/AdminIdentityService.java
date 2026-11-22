package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.RefreshToken;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserRole;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.AdminRefreshSessionResponse;
import com.voltcanary.identity.dto.AdminRevokeSessionsResponse;
import com.voltcanary.identity.dto.AdminUpdateUserRoleRequest;
import com.voltcanary.identity.dto.AdminUpdateUserStatusRequest;
import com.voltcanary.identity.dto.AdminUserResponse;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.exception.AuthorizationFailedException;
import com.voltcanary.identity.repository.RefreshTokenRepository;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.AuthenticatedUser;
import com.voltcanary.identity.security.JwtService;
import com.voltcanary.identity.security.RefreshTokenService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class AdminIdentityService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final IdentityLifecycleOutboxService identityLifecycleOutboxService;

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(String authorizationHeader) {
        return listUsers(authorizationHeader, 0, 100, "");
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(
        String authorizationHeader,
        int page,
        int size,
        String search
    ) {
        requireAdmin(authorizationHeader);
        if (page < 0 || size < 1 || size > 200) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "page must be nonnegative and size must be between 1 and 200"
            );
        }
        String pattern =
            "%" +
            search
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") +
            "%";
        org.springframework.data.jpa.domain.Specification<User> matching = (root, query, builder) ->
            builder.or(
                builder.like(builder.lower(root.get("email")), pattern, '!'),
                builder.like(builder.lower(root.get("role").as(String.class)), pattern, '!'),
                builder.like(builder.lower(root.get("status").as(String.class)), pattern, '!')
            );
        return userRepository
            .findAll(
                matching,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
            )
            .stream()
            .map(this::toAdminUserResponse)
            .toList();
    }

    @Transactional
    public AdminUserResponse updateUserStatus(
        String authorizationHeader,
        UUID userId,
        AdminUpdateUserStatusRequest request
    ) {
        requireAdmin(authorizationHeader);
        User user = userRepository
            .findById(userId)
            .orElseThrow(() -> new AuthenticationFailedException("User not found"));
        user.setStatus(request.status());
        user.setUpdatedAt(Instant.now());
        if (request.status() != UserStatus.ACTIVE) {
            refreshTokenService.revokeAllForUser(user.getId());
        }
        identityLifecycleOutboxService.enqueueAccountLifecycle(user);
        return toAdminUserResponse(user);
    }

    @Transactional
    public AdminUserResponse deleteUser(String authorizationHeader, UUID userId) {
        requireAdmin(authorizationHeader);
        User user = userRepository
            .findById(userId)
            .orElseThrow(() -> new AuthenticationFailedException("User not found"));
        user.setStatus(UserStatus.DELETED);
        user.setUpdatedAt(Instant.now());
        refreshTokenService.revokeAllForUser(user.getId());
        identityLifecycleOutboxService.enqueueAccountLifecycle(user);
        return toAdminUserResponse(user);
    }

    @Transactional
    public AdminUserResponse updateUserRole(
        String authorizationHeader,
        UUID userId,
        AdminUpdateUserRoleRequest request
    ) {
        requireAdmin(authorizationHeader);
        User user = userRepository
            .findById(userId)
            .orElseThrow(() -> new AuthenticationFailedException("User not found"));
        user.setRole(request.role());
        user.setUpdatedAt(Instant.now());
        refreshTokenService.revokeAllForUser(user.getId());
        return toAdminUserResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AdminRefreshSessionResponse> listUserSessions(
        String authorizationHeader,
        UUID userId
    ) {
        requireAdmin(authorizationHeader);
        if (!userRepository.existsById(userId)) {
            throw new AuthenticationFailedException("User not found");
        }
        return refreshTokenRepository
            .findByUserId(userId)
            .stream()
            .sorted(Comparator.comparing(RefreshToken::getCreatedAt).reversed())
            .map(this::toAdminRefreshSessionResponse)
            .toList();
    }

    @Transactional
    public AdminRevokeSessionsResponse revokeUserSessions(String authorizationHeader, UUID userId) {
        requireAdmin(authorizationHeader);
        if (!userRepository.existsById(userId)) {
            throw new AuthenticationFailedException("User not found");
        }
        return new AdminRevokeSessionsResponse(refreshTokenService.revokeAllForUser(userId));
    }

    private void requireAdmin(String authorizationHeader) {
        AuthenticatedUser user = jwtService.parseBearerToken(authorizationHeader);
        User current = userRepository
            .findById(user.id())
            .orElseThrow(() -> new AuthenticationFailedException("Invalid access token"));
        if (current.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid access token");
        }
        if (!UserRole.ADMIN.name().equals(user.role()) || current.getRole() != UserRole.ADMIN) {
            throw new AuthorizationFailedException("Admin role is required");
        }
    }

    private AdminUserResponse toAdminUserResponse(User user) {
        return new AdminUserResponse(
            user.getId(),
            user.getEmail(),
            user.getRole(),
            user.getStatus(),
            user.isEmailVerified(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    private AdminRefreshSessionResponse toAdminRefreshSessionResponse(RefreshToken token) {
        Instant now = Instant.now();
        boolean active = token.getRevokedAt() == null && token.getExpiresAt().isAfter(now);
        return new AdminRefreshSessionResponse(
            token.getId(),
            token.getUser().getId(),
            token.getCreatedAt(),
            token.getExpiresAt(),
            token.getRevokedAt(),
            active
        );
    }
}
