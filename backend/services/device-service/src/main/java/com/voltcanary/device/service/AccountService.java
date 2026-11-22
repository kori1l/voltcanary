package com.voltcanary.device.service;

import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.dto.AccountResponse;
import com.voltcanary.device.exception.AccountInactiveException;
import com.voltcanary.device.repository.AccountRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountProvisioning provisioning;
    private final AccountDeletionCleanupService accountDeletionCleanupService;

    @Transactional
    public Account currentAccount(AuthenticatedUser user) {
        Account account = accountRepository.findByUserId(user.id()).orElseGet(() -> createAccount(user));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Device account is " + account.getStatus().name().toLowerCase());
        }
        return account;
    }

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getUserId(), account.getName(), account.getStatus().name());
    }

    @Transactional
    public Account syncUser(UUID userId, String email, AccountStatus status) {
        Account account = accountRepository.findByUserId(userId)
                .orElseGet(() -> createAccount(userId, email));
        Instant now = Instant.now();
        account.setStatus(status);
        account.setUpdatedAt(now);
        if (status == AccountStatus.DISABLED) {
            account.setDisabledAt(now);
        } else if (status == AccountStatus.DELETED) {
            account.setDeletedAt(now);
            accountDeletionCleanupService.cleanupDeletedAccount(account.getId(), now);
        } else {
            account.setDisabledAt(null);
            account.setDeletedAt(null);
        }
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findByUserId(UUID userId) {
        return accountRepository.findByUserId(userId);
    }

    private Account createAccount(AuthenticatedUser user) {
        return createAccount(user.id(), user.email());
    }

    private Account createAccount(UUID userId, String email) {
        return provisioning.ensure(userId, email);
    }
}
