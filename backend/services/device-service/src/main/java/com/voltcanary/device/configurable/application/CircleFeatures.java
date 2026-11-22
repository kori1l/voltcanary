package com.voltcanary.device.configurable.application;

import com.voltcanary.vcl.VclCircleRevision;
import com.voltcanary.vcl.VclException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative WGS84 circle revisions scoped to a current account owner. */
@Service
@RequiredArgsConstructor
public class CircleFeatures {

    public record Page(List<VclCircleRevision> items, UUID nextAfter) {}

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Page list(UUID actor, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new VclException("SPATIAL_PAGE_INVALID", "");
        UUID account = account(actor, false);
        String sql = """
        SELECT r.feature_id,r.revision,r.longitude,r.latitude,r.radius_metres,r.digest
        FROM cfg_circle_feature f JOIN cfg_circle_feature_revision r
          ON f.id=r.feature_id AND f.head_revision=r.revision
        WHERE f.account_id=?
        """;
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(account);
        if (after != null) {
            sql += " AND f.id>?";
            parameters.add(after);
        }
        parameters.add(limit + 1);
        var revisions = jdbc.query(
            sql + " ORDER BY f.id LIMIT ?",
            (row, index) ->
                new VclCircleRevision(
                    row.getObject("feature_id", UUID.class),
                    account,
                    row.getLong("revision"),
                    new VclCircleRevision.Definition(
                        row.getDouble("longitude"),
                        row.getDouble("latitude"),
                        row.getString("radius_metres")
                    ),
                    row.getString("digest")
                ),
            parameters.toArray()
        );
        if (!account.equals(account(actor, false))) throw new VclException(
            "OWNER_ACCESS_REVOKED",
            ""
        );
        if (revisions.size() <= limit) return new Page(List.copyOf(revisions), null);
        return new Page(List.copyOf(revisions.subList(0, limit)), revisions.get(limit - 1).id());
    }

    @Transactional
    public VclCircleRevision revise(
        UUID actor,
        UUID id,
        long expectedRevision,
        VclCircleRevision.Definition geometry
    ) {
        if (expectedRevision < 0 || expectedRevision >= 128 || geometry == null) {
            throw new VclException("SPATIAL_REVISION_INVALID", "");
        }
        UUID account = account(actor, true);
        var heads = jdbc.queryForList(
            "SELECT head_revision FROM cfg_circle_feature WHERE id=? AND account_id=?",
            Long.class,
            id,
            account
        );
        long head = heads.isEmpty() ? 0 : heads.getFirst();
        long next = expectedRevision + 1;
        if (head >= next) {
            var previous = revision(account, id, next);
            if (previous.geometry().equals(geometry)) return previous;
            throw new VclException("SPATIAL_REVISION_CHANGED", "");
        }
        if (head != expectedRevision) throw new VclException("SPATIAL_REVISION_CHANGED", "");
        if (head == 0) {
            if (
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cfg_circle_feature WHERE account_id=?",
                    Long.class,
                    account
                ) >= 1024
            ) {
                throw new VclException("SPATIAL_FEATURE_LIMIT", "");
            }
            try {
                jdbc.update(
                    "INSERT INTO cfg_circle_feature(id,account_id,head_revision) VALUES (?,?,?)",
                    id,
                    account,
                    next
                );
            } catch (org.springframework.dao.DuplicateKeyException conflict) {
                throw new VclException("SPATIAL_FEATURE_UNAVAILABLE", "");
            }
        } else {
            jdbc.update(
                "UPDATE cfg_circle_feature SET head_revision=? WHERE id=? AND account_id=?",
                next,
                id,
                account
            );
        }
        var result = VclCircleRevision.create(id, account, next, geometry);
        jdbc.update(
            """
            INSERT INTO cfg_circle_feature_revision(feature_id,revision,longitude,latitude,radius_metres,digest)
            VALUES (?,?,?,?,?,?)
            """,
            id,
            next,
            geometry.longitude(),
            geometry.latitude(),
            geometry.radiusMetres(),
            result.digest()
        );
        return result;
    }

    @Transactional(readOnly = true)
    public VclCircleRevision describe(UUID actor, UUID expectedAccount, UUID id, long revision) {
        return describe(actor, expectedAccount, id, revision, false);
    }

    @Transactional(readOnly = true)
    public VclCircleRevision describe(
        UUID actor,
        UUID expectedAccount,
        UUID id,
        long revision,
        boolean requireCurrent
    ) {
        if (revision < 1) throw new VclException("SPATIAL_REVISION_INVALID", "");
        UUID account = account(actor, false);
        if (expectedAccount != null && !expectedAccount.equals(account)) throw new VclException(
            "OWNER_ACCESS_REVOKED",
            ""
        );
        var result = revision(account, id, revision);
        if (requireCurrent) {
            var heads = jdbc.queryForList(
                "SELECT head_revision FROM cfg_circle_feature WHERE id=? AND account_id=?",
                Long.class,
                id,
                account
            );
            if (heads.size() != 1 || heads.getFirst() != revision) throw new VclException(
                "SPATIAL_REVISION_CHANGED",
                ""
            );
        }
        if (!account.equals(account(actor, false))) throw new VclException(
            "OWNER_ACCESS_REVOKED",
            ""
        );
        return result;
    }

    private VclCircleRevision revision(UUID account, UUID id, long revision) {
        var rows = jdbc.query(
            """
            SELECT r.longitude,r.latitude,r.radius_metres,r.digest FROM cfg_circle_feature_revision r
            JOIN cfg_circle_feature f ON f.id=r.feature_id
            WHERE f.account_id=? AND r.feature_id=? AND r.revision=?
            """,
            (row, index) ->
                new VclCircleRevision(
                    id,
                    account,
                    revision,
                    new VclCircleRevision.Definition(
                        row.getDouble(1),
                        row.getDouble(2),
                        row.getString(3)
                    ),
                    row.getString(4)
                ),
            account,
            id,
            revision
        );
        if (rows.isEmpty()) throw new VclException("SPATIAL_FEATURE_UNAVAILABLE", "");
        return rows.getFirst();
    }

    private UUID account(UUID actor, boolean lock) {
        var rows = jdbc.queryForList(
            "SELECT id FROM accounts WHERE user_id=? AND status='ACTIVE'" +
                (lock ? " FOR UPDATE" : ""),
            UUID.class,
            actor
        );
        if (rows.size() != 1) throw new VclException("OWNER_ACCESS_REVOKED", "");
        return rows.getFirst();
    }
}
