package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserRole;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.RegisterRequest;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.exception.DuplicateEmailException;
import com.voltcanary.identity.repository.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    private static final UserRole DEFAULT_ROLE = UserRole.USER;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRegistrationWrites writes;

    @Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        Instant now = Instant.now();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmailVerified(false);
        user.setRole(DEFAULT_ROLE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            User saved = writes.save(user);
            return new RegisterResponse(saved.getId(), saved.getEmail());
        } catch (DataIntegrityViolationException conflict) {
            if (isUniqueConflict(conflict) && userRepository.existsByEmail(email)) {
                throw new DuplicateEmailException(email);
            }
            throw conflict;
        }
    }

    private boolean isUniqueConflict(DataIntegrityViolationException conflict) {
        for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
