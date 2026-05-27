package com.voltcanary.device.account;

import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.shared.AccountInactiveException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

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
        Instant now = Instant.now();
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setName(defaultAccountName(email));
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }

    private String defaultAccountName(String email) {
        if (email == null || email.isBlank()) {
            return "Personal account";
        }
        return email + " account";
    }
}
