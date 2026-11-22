package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded state reconstruction from captured versions, including explicit deletion transitions. */
final class NativeHistoryState {

    record Sql(String cte, String table, List<Object> parameters) {}

    private NativeHistoryState() {}

    static Sql sql(NativeDataset dataset, NativeReadScope access, long first, long through) {
        var parameters = new ArrayList<Object>();
        parameters.add(dataset.schema().id());
        parameters.add(dataset.generation());
        parameters.add(first);
        parameters.add(through);
        String rows = access == null ? "TRUE" : access.rowPredicate(parameters);
        parameters.add(dataset.schema().id());
        parameters.add(dataset.generation());
        String cte =
            """
            WITH latest AS (
                SELECT record_id,MAX(record_version) AS chosen_version FROM cfg_native_history_checkpoint
                WHERE dataset_id=? AND generation=? AND data_version>=? AND data_version<=? AND capture_kind<>'RETIREMENT' AND (
            """ +
            rows +
            ") GROUP BY record_id), chosen AS (" +
            "SELECT p.* FROM cfg_native_history_checkpoint p JOIN latest l ON l.record_id=p.record_id AND l.chosen_version=p.record_version " +
            "WHERE p.dataset_id=? AND p.generation=?)";
        return new Sql(
            cte,
            NativeSqlLayout.identifier(new NativeSqlLayout(dataset).historyTable()),
            List.copyOf(parameters)
        );
    }

    /** Validate the entire authorized state before a predicate can filter corrupt values out. */
    static void verify(Connection connection, NativeDataset dataset, Sql sql) throws SQLException {
        var fields = dataset
            .schema()
            .fields()
            .stream()
            .map(field -> UUID.fromString(field.path("id").asText()))
            .toList();
        try (
            var query = connection.prepareStatement(
                sql.cte() +
                    " SELECT h.*,c.record_id AS captured_record,c.deleted AS captured_deleted,c.value_digest AS captured_digest FROM chosen c " +
                    "LEFT JOIN " +
                    sql.table() +
                    " h ON h.record_id=c.record_id AND h.record_version=c.record_version LIMIT 10001"
            )
        ) {
            query.setQueryTimeout(2);
            query.setFetchSize(64);
            int parameter = 1;
            for (Object value : sql.parameters()) query.setObject(parameter++, value);
            int count = 0;
            long bytes = 0;
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (++count > NativeHistoryCapture.MAX_BASELINE_ROWS) throw new VclException(
                        "HISTORY_BUDGET_EXCEEDED",
                        ""
                    );
                    if (
                        rows.getObject("record_id") == null ||
                        rows.getBoolean("deleted") != rows.getBoolean("captured_deleted")
                    ) {
                        throw new VclException("HISTORY_INTEGRITY", "");
                    }
                    var row = NativeRowCodec.read(rows, dataset, fields);
                    bytes += NativeHistoryCapture.bytes(row);
                    if (bytes > NativeHistoryCapture.MAX_STATE_BYTES) throw new VclException(
                        "HISTORY_BUDGET_EXCEEDED",
                        ""
                    );
                    if (
                        !row.recordId().equals(rows.getObject("captured_record", UUID.class)) ||
                        !NativeHistoryCapture.digest(
                            dataset,
                            row,
                            rows.getBoolean("deleted")
                        ).equals(rows.getString("captured_digest"))
                    ) {
                        throw new VclException("HISTORY_INTEGRITY", "");
                    }
                }
            }
        }
    }
}
