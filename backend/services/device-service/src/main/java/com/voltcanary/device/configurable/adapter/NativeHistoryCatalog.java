package com.voltcanary.device.configurable.adapter;

import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Real dense history catalog checks; historical read readiness is not inferred from table naming. */
final class NativeHistoryCatalog {

    private record Column(String type, int size, int scale, boolean nullable) {}

    private NativeHistoryCatalog() {}

    static void verify(Connection connection, NativeSqlLayout layout) throws SQLException {
        var columns = new HashMap<String, Column>();
        try (
            var rows = connection
                .getMetaData()
                .getColumns(
                    connection.getCatalog(),
                    connection.getSchema(),
                    layout.historyTable(),
                    "%"
                )
        ) {
            while (rows.next())
                columns.put(
                    rows.getString("COLUMN_NAME"),
                    new Column(
                        rows.getString("TYPE_NAME").toUpperCase(java.util.Locale.ROOT),
                        rows.getInt("COLUMN_SIZE"),
                        rows.getInt("DECIMAL_DIGITS"),
                        rows.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls
                    )
                );
        }
        var expected = new HashSet<>(
            Set.of("record_id", "record_version", "recorded_at", "deleted")
        );
        layout.columns().forEach(column -> expected.add(column.name()));
        require(columns.keySet().equals(expected));
        require(
            columns.get("record_id").type().equals("UUID") && !columns.get("record_id").nullable()
        );
        require(
            Set.of("BIGINT", "INT8").contains(columns.get("record_version").type()) &&
                !columns.get("record_version").nullable()
        );
        require(
            Set.of("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE").contains(
                columns.get("recorded_at").type()
            ) && !columns.get("recorded_at").nullable()
        );
        require(
            Set.of("BOOL", "BOOLEAN").contains(columns.get("deleted").type()) &&
                !columns.get("deleted").nullable()
        );
        for (var expectedColumn : layout.columns()) {
            var actual = columns.get(expectedColumn.name());
            require(actual.nullable());
            boolean type = switch (expectedColumn.sqlType().split("\\(")[0]) {
                case "UUID" -> actual.type().equals("UUID");
                case "BOOLEAN" -> Set.of("BOOL", "BOOLEAN").contains(actual.type());
                case "BIGINT" -> Set.of("BIGINT", "INT8").contains(actual.type());
                case "NUMERIC" -> Set.of("NUMERIC", "DECIMAL").contains(actual.type());
                case "VARCHAR" -> Set.of("VARCHAR", "CHARACTER VARYING").contains(actual.type());
                case "DATE" -> actual.type().equals("DATE");
                case "DOUBLE PRECISION" -> Set.of("FLOAT8", "DOUBLE PRECISION").contains(
                    actual.type()
                );
                case "TIMESTAMP" -> Set.of("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE").contains(
                    actual.type()
                );
                default -> false;
            };
            require(type);
            if (expectedColumn.sqlType().startsWith("NUMERIC(")) {
                String[] sizes = expectedColumn
                    .sqlType()
                    .substring(8, expectedColumn.sqlType().length() - 1)
                    .split(",");
                require(
                    actual.size() == Integer.parseInt(sizes[0]) &&
                        actual.scale() == Integer.parseInt(sizes[1])
                );
            }
            if (expectedColumn.sqlType().startsWith("VARCHAR(")) {
                require(
                    actual.size() ==
                        Integer.parseInt(
                            expectedColumn
                                .sqlType()
                                .substring(8, expectedColumn.sqlType().length() - 1)
                        )
                );
            }
        }
        var primary = new java.util.TreeMap<Integer, String>();
        try (
            var rows = connection
                .getMetaData()
                .getPrimaryKeys(
                    connection.getCatalog(),
                    connection.getSchema(),
                    layout.historyTable()
                )
        ) {
            while (rows.next()) primary.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        require(
            java.util.List.copyOf(primary.values()).equals(
                java.util.List.of("record_id", "record_version")
            )
        );
    }

    private static void require(boolean condition) {
        if (!condition) throw new VclException("HISTORY_INTEGRITY", "");
    }
}
