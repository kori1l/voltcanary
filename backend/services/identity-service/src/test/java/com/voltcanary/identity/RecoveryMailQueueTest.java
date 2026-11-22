package com.voltcanary.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.service.RecoveryMail;
import com.voltcanary.identity.service.RecoveryMailCipher;
import com.voltcanary.identity.service.RecoveryMailQueue;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(RecoveryMailQueueTest.Config.class)
class RecoveryMailQueueTest {

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            var source = new DriverManagerDataSource(
                "jdbc:h2:mem:recovery-queue;DB_CLOSE_DELAY=-1",
                "sa",
                ""
            );
            new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V010__recovery_mail_outbox.sql")
            ).execute(source);
            return source;
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TransactionTemplate transactions(DataSourceTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        RecoveryMailCipher cipher() {
            var properties = new IdentityAuthProperties();
            properties.setMailEncryptionKey("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            return new RecoveryMailCipher(properties, new ObjectMapper());
        }

        @Bean
        RecoveryMailQueue queue(JdbcTemplate jdbc, RecoveryMailCipher cipher) {
            return new RecoveryMailQueue(jdbc, cipher);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private RecoveryMailQueue queue;

    @Autowired
    private RecoveryMailCipher cipher;

    @BeforeEach
    void clean() {
        jdbc.update("delete from recovery_mail_outbox");
    }

    @Test
    void deliveryIsEncryptedAndCommittedWithItsOwningTransaction() {
        var message = new RecoveryMail.Message(
            "private@example.test",
            "password reset",
            "private-code"
        );
        UUID id = transactions.execute(status ->
            queue.enqueue(message, Instant.now().plusSeconds(600))
        );
        String ciphertext = jdbc.queryForObject(
            "select payload_ciphertext from recovery_mail_outbox where id=?",
            String.class,
            id
        );
        assertThat(ciphertext).doesNotContain(message.code(), message.email());
        assertThat(cipher.decrypt(ciphertext)).isEqualTo(message);
        transactions.executeWithoutResult(status -> {
            queue.enqueue(message, Instant.now().plusSeconds(600));
            status.setRollbackOnly();
        });
        assertThat(
            jdbc.queryForObject("select count(*) from recovery_mail_outbox", Integer.class)
        ).isEqualTo(1);
    }

    @Test
    void aRestartedWorkerRecoversTheLeaseAndRejectsOldCompletionAndFailure() {
        UUID id = transactions.execute(status ->
            queue.enqueue(
                new RecoveryMail.Message("user@example.test", "password reset", "code"),
                Instant.now().plusSeconds(600)
            )
        );
        var original = queue.claim();
        assertThat(queue.claim()).isNull();
        jdbc.update(
            "update recovery_mail_outbox set locked_at=? where id=?",
            Timestamp.from(Instant.now().minusSeconds(121)),
            id
        );
        // A new service instance has no process-local knowledge of the earlier claim.
        var restarted = new RecoveryMailQueue(jdbc, cipher);
        var replacement = transactions.execute(status -> restarted.claim());
        assertThat(replacement.attempt()).isEqualTo(original.attempt() + 1);
        assertThat(replacement.owner()).isNotEqualTo(original.owner());
        queue.delivered(original);
        queue.failed(original, "STALE");
        assertThat(
            jdbc.queryForObject(
                "select status from recovery_mail_outbox where id=?",
                String.class,
                id
            )
        ).isEqualTo("PROCESSING");
        queue.delivered(replacement);
        queue.failed(original, "STALE");
        assertThat(
            jdbc.queryForObject(
                "select status from recovery_mail_outbox where id=?",
                String.class,
                id
            )
        ).isEqualTo("SENT");
        assertThat(
            jdbc.queryForObject(
                "select payload_ciphertext from recovery_mail_outbox where id=?",
                String.class,
                id
            )
        ).isNull();
    }

    @Test
    void aFailedDeliveryIsRetriedAndExpiredCodesAreDiscarded() {
        UUID id = transactions.execute(status ->
            queue.enqueue(
                new RecoveryMail.Message("user@example.test", "password reset", "code"),
                Instant.now().plusSeconds(600)
            )
        );
        queue.failed(queue.claim(), "SMTP_UNAVAILABLE");
        assertThat(queue.claim()).isNull();
        jdbc.update(
            "update recovery_mail_outbox set next_attempt_at=? where id=?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            id
        );
        assertThat(queue.claim().attempt()).isEqualTo(2);
        jdbc.update(
            "update recovery_mail_outbox set expires_at=? where id=?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            id
        );
        assertThat(queue.claim()).isNull();
        assertThat(
            jdbc.queryForObject("select count(*) from recovery_mail_outbox", Integer.class)
        ).isZero();
    }
}
