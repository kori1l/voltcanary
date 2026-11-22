package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

interface NativeDataTransactions {
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection, NativeReadScope scope) throws SQLException;
    }

    <T> T run(UUID account, UUID actor, Work<T> work);

    static NativeDataTransactions owner(DataSource source) {
        return new NativeDataTransactions() {
            public <T> T run(UUID account, UUID actor, Work<T> work) {
                return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) ->
                    work.run(connection, NativeReadScope.owner(scope))
                );
            }
        };
    }

    static NativeDataTransactions shared(
        DataSource source,
        NativeWorkspaceIdentity identity,
        NativeSharedAccess access
    ) {
        return shared(source, identity, access, false);
    }

    static NativeDataTransactions shared(
        DataSource source,
        NativeWorkspaceIdentity identity,
        NativeSharedAccess access,
        boolean writes
    ) {
        return new NativeDataTransactions() {
            public <T> T run(UUID account, UUID actor, Work<T> work) {
                if (
                    !access.account().equals(account) || !access.actor().equals(actor)
                ) throw new VclException("DATA_ACCESS_DENIED", "");
                var membership = identity.membership(access.workspace(), actor);
                var resources = writes
                    ? identity.resources(access.workspace(), account, actor)
                    : null;
                record Result<T>(T value, String scope) {}
                Result<T> result = transaction(connection -> {
                    var scope = NativeWorkspaceGrantScope.load(connection, access, membership);
                    if (resources != null) scope = scope.withResources(resources);
                    return new Result<>(work.run(connection, scope), scope.digest());
                });
                var renewed = identity.membership(access.workspace(), actor);
                var freshResources = writes
                    ? identity.resources(access.workspace(), account, actor)
                    : null;
                if (!membership.digest().equals(renewed.digest())) throw new VclException(
                    "ACCESS_SCOPE_CHANGED",
                    ""
                );
                transaction(connection -> {
                    var scope = NativeWorkspaceGrantScope.load(connection, access, renewed);
                    if (freshResources != null) scope = scope.withResources(freshResources);
                    if (!result.scope().equals(scope.digest())) throw new VclException(
                        "ACCESS_SCOPE_CHANGED",
                        ""
                    );
                    return null;
                });
                return result.value();
            }

            private <T> T transaction(SqlWork<T> work) {
                try (var connection = source.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        JdbcNativeDatasetCatalog.timeouts(connection);
                        T result = work.run(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException failure) {
                        connection.rollback();
                        throw failure;
                    }
                } catch (SQLException failure) {
                    throw NativeRowTransactions.failure(failure);
                }
            }
        };
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
