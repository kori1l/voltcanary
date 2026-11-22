package com.voltcanary.identity.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecoveryMailQueue {

    public record Claim(UUID id, String ciphertext, String owner, int attempt) {
        @Override
        public String toString() {
            return "RecoveryMailClaim[id=" + id + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final RecoveryMailCipher cipher;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(RecoveryMail.Message message, Instant expires) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
            "insert into recovery_mail_outbox(id,payload_ciphertext,status,attempts,next_attempt_at,created_at,expires_at) values(?,?,'PENDING',0,?,?,?)",
            id,
            cipher.encrypt(message),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(expires)
        );
        return id;
    }

    @Transactional
    public Claim claim() {
        Instant now = Instant.now();
        jdbc.update("delete from recovery_mail_outbox where expires_at<=?", Timestamp.from(now));
        var due = jdbc.query(
            "select id,payload_ciphertext,attempts from recovery_mail_outbox where expires_at>? and " +
                "((status in ('PENDING','RETRYING') and next_attempt_at<=?) or (status='PROCESSING' and locked_at<?)) " +
                "order by created_at,id limit 1 for update skip locked",
            (rs, index) ->
                new Claim(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    UUID.randomUUID().toString(),
                    rs.getInt(3) + 1
                ),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.minusSeconds(120))
        );
        if (due.isEmpty()) return null;
        var claim = due.getFirst();
        jdbc.update(
            "update recovery_mail_outbox set status='PROCESSING',attempts=?,locked_at=?,locked_by=? where id=?",
            claim.attempt(),
            Timestamp.from(now),
            claim.owner(),
            claim.id()
        );
        return claim;
    }

    @Transactional
    public void delivered(Claim claim) {
        jdbc.update(
            "update recovery_mail_outbox set status='SENT',payload_ciphertext=null,locked_at=null,locked_by=null,last_error_code=null " +
                "where id=? and status='PROCESSING' and locked_by=? and attempts=?",
            claim.id(),
            claim.owner(),
            claim.attempt()
        );
    }

    @Transactional
    public void failed(Claim claim, String errorCode) {
        long delay = Math.min(300, 5L << Math.min(6, claim.attempt() - 1));
        jdbc.update(
            "update recovery_mail_outbox set status='RETRYING',next_attempt_at=?,locked_at=null,locked_by=null,last_error_code=? " +
                "where id=? and status='PROCESSING' and locked_by=? and attempts=?",
            Timestamp.from(Instant.now().plusSeconds(delay)),
            errorCode,
            claim.id(),
            claim.owner(),
            claim.attempt()
        );
    }
}
