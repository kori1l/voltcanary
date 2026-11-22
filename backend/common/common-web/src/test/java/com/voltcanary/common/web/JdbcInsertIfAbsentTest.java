package com.voltcanary.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcInsertIfAbsentTest {

    @Test
    void duplicateCompositeKeyPreservesTheWinnerAndTheTransactionCanContinue() {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:insert-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(
            "create table receipts (account_id uuid, event_id uuid, payload varchar(100), created_at timestamp, primary key(account_id,event_id))"
        );
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID account = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        var columns = List.of("account_id", "event_id", "payload", "created_at");
        var keys = List.of("account_id", "event_id");
        transaction.executeWithoutResult(status -> {
            assertThat(
                JdbcInsertIfAbsent.insert(
                    jdbc,
                    "receipts",
                    columns,
                    keys,
                    account,
                    event,
                    "original",
                    Timestamp.from(Instant.now())
                )
            ).isEqualTo(1);
            assertThat(
                JdbcInsertIfAbsent.insert(
                    jdbc,
                    "receipts",
                    columns,
                    keys,
                    account,
                    event,
                    "duplicate",
                    Timestamp.from(Instant.now())
                )
            ).isZero();
            jdbc.update(
                "update receipts set payload='committed' where account_id=? and event_id=?",
                account,
                event
            );
        });
        assertThat(jdbc.queryForObject("select payload from receipts", String.class)).isEqualTo(
            "committed"
        );
        transaction.executeWithoutResult(status -> {
            JdbcInsertIfAbsent.insert(
                jdbc,
                "receipts",
                columns,
                keys,
                account,
                UUID.randomUUID(),
                "rolled back",
                null
            );
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from receipts", Integer.class)).isEqualTo(
            1
        );
    }
}
