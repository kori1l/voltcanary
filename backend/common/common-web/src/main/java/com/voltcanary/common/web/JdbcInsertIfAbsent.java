package com.voltcanary.common.web;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Duplicate delivery is a SQL outcome, never a failed PostgreSQL transaction. */
public final class JdbcInsertIfAbsent {

    private JdbcInsertIfAbsent() {}

    public static int insert(
        JdbcTemplate jdbc,
        String table,
        List<String> columns,
        List<String> keys,
        Object... values
    ) {
        identifier(table);
        columns.forEach(JdbcInsertIfAbsent::identifier);
        keys.forEach(JdbcInsertIfAbsent::identifier);
        if (columns.size() != values.length || keys.isEmpty() || !columns.containsAll(keys)) {
            throw new IllegalArgumentException("Invalid idempotent insert columns");
        }
        return jdbc.execute(
            (ConnectionCallback<Integer>) connection -> {
                String names = String.join(",", columns);
                String placeholders = columns
                    .stream()
                    .map(column -> "?")
                    .collect(Collectors.joining(","));
                String sql;
                if ("H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                    String match = keys
                        .stream()
                        .map(key -> "target." + key + "=incoming." + key)
                        .collect(Collectors.joining(" and "));
                    String incoming = columns
                        .stream()
                        .map(column -> "incoming." + column)
                        .collect(Collectors.joining(","));
                    sql =
                        "merge into " +
                        table +
                        " target using (values(" +
                        placeholders +
                        ")) incoming(" +
                        names +
                        ") on " +
                        match +
                        " when not matched then insert(" +
                        names +
                        ") values(" +
                        incoming +
                        ")";
                } else {
                    sql =
                        "insert into " +
                        table +
                        "(" +
                        names +
                        ") values(" +
                        placeholders +
                        ") on conflict(" +
                        String.join(",", keys) +
                        ") do nothing";
                }
                try (var statement = connection.prepareStatement(sql)) {
                    for (int index = 0; index < values.length; index++) statement.setObject(
                        index + 1,
                        values[index]
                    );
                    return statement.executeUpdate();
                }
            }
        );
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException(
            "Invalid SQL identifier"
        );
    }
}
