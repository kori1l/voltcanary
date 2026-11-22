package com.voltcanary.device.configurable.adapter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

final class NativeRowUnitOfWork {

    @FunctionalInterface
    interface Operation<T> {
        T run(Connection connection, String scope) throws SQLException;
    }

    private NativeRowUnitOfWork() {}

    static <T> T run(DataSource source, UUID account, UUID actor, Operation<T> operation) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcNativeDatasetCatalog.timeouts(connection);
                String scope = NativeRowTransactions.actor(connection, account, actor);
                T result = operation.run(connection, scope);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw NativeRowTransactions.failure(error);
        }
    }
}
