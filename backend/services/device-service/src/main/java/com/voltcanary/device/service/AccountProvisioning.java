package com.voltcanary.device.service;

import com.voltcanary.device.domain.Account;
import com.voltcanary.device.repository.AccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Concurrent first requests converge to the one account owned by this user. */
@Service
public class AccountProvisioning {

    private final JdbcTemplate jdbc;
    private final AccountRepository repository;
    private final boolean h2;

    public AccountProvisioning(JdbcTemplate jdbc, AccountRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
        h2 = Boolean.TRUE.equals(
            jdbc.execute(
                (ConnectionCallback<Boolean>) connection ->
                    connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2")
            )
        );
    }

    @Transactional
    public Account ensure(UUID userId, String email) {
        UUID id = UUID.randomUUID();
        String name = email == null || email.isBlank() ? "Personal account" : email + " account";
        Timestamp now = Timestamp.from(Instant.now());
        if (h2) {
            try {
                jdbc.update(
                    "merge into accounts target using (values(?,?,?,?,?,?)) incoming(id,user_id,name,status,created_at,updated_at) " +
                        "on target.user_id=incoming.user_id when not matched then insert(id,user_id,name,status,created_at,updated_at) " +
                        "values(incoming.id,incoming.user_id,incoming.name,incoming.status,incoming.created_at,incoming.updated_at)",
                    id,
                    userId,
                    name,
                    "ACTIVE",
                    now,
                    now
                );
            } catch (DuplicateKeyException concurrentInsert) {
                // H2 statement failures do not poison the transaction; read the committed winner.
            }
        } else {
            jdbc.update(
                "insert into accounts(id,user_id,name,status,created_at,updated_at) values(?,?,?,'ACTIVE',?,?) on conflict(user_id) do nothing",
                id,
                userId,
                name,
                now,
                now
            );
        }
        return repository
            .findByUserId(userId)
            .orElseThrow(() ->
                new IllegalStateException("Account provisioning did not produce an account")
            );
    }
}
