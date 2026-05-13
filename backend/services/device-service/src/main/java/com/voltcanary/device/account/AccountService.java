package com.voltcanary.device.account;

import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
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
        return accountRepository.findByUserId(user.id()).orElseGet(() -> createAccount(user));
    }

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getUserId(), account.getName());
    }

    private Account createAccount(AuthenticatedUser user) {
        Instant now = Instant.now();
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(user.id());
        account.setName(defaultAccountName(user));
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }

    private String defaultAccountName(AuthenticatedUser user) {
        if (user.email() == null || user.email().isBlank()) {
            return "Personal account";
        }
        return user.email() + " account";
    }
}
