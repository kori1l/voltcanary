package com.voltcanary.dashboard.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardRecords.Cursor;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.domain.DashboardRecords.Idempotency;
import com.voltcanary.dashboard.domain.DashboardRecords.Preferences;
import com.voltcanary.dashboard.domain.DashboardRecords.Revision;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDashboardStorage implements DashboardStorage {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcDashboardStorage(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void lockActor(DashboardActor actor) {
        // A user-wide transaction lock also enforces quota across account changes.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (row, index) -> true, actor.userId().toString());
    }

    @Override
    public long activeCount(DashboardActor actor) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM dashboards WHERE owner_user_id=? AND status='ACTIVE'",
                Long.class, actor.userId());
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Dashboard> findOwned(DashboardActor actor, UUID id) {
        return jdbc.query("SELECT * FROM dashboards WHERE id=? AND owner_user_id=? AND account_id=?",
                this::dashboardRow, id, actor.userId(), actor.accountId()).stream().findFirst();
    }

    @Override
    public List<Dashboard> list(DashboardActor actor, String status, Cursor cursor, int limit) {
        String sql = "SELECT * FROM dashboards WHERE owner_user_id=? AND account_id=?";
        List<Object> args = new ArrayList<>(List.of(actor.userId(), actor.accountId()));
        if (!"ALL".equals(status)) {
            sql += " AND status=?";
            args.add(status);
        }
        if (cursor != null) {
            sql += " AND (created_at,id)>(?,?)";
            args.add(Timestamp.from(cursor.createdAt()));
            args.add(cursor.id());
        }
        sql += " ORDER BY created_at,id LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, this::dashboardRow, args.toArray());
    }

    @Override
    public void insert(Dashboard dashboard) {
        jdbc.update("""
                INSERT INTO dashboards(id,owner_user_id,account_id,name,description,status,
                    published_revision,schema_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, dashboard.id(), dashboard.ownerUserId(), dashboard.accountId(), dashboard.name(),
                dashboard.description(), dashboard.status(), dashboard.publishedRevision(), dashboard.schemaVersion(),
                Timestamp.from(dashboard.createdAt()), Timestamp.from(dashboard.updatedAt()));
    }

    @Override
    public void update(Dashboard dashboard) {
        jdbc.update("""
                UPDATE dashboards SET name=?,description=?,status=?,published_revision=?,schema_version=?,updated_at=?
                WHERE id=? AND owner_user_id=? AND account_id=?
                """, dashboard.name(), dashboard.description(), dashboard.status(), dashboard.publishedRevision(),
                dashboard.schemaVersion(), Timestamp.from(dashboard.updatedAt()), dashboard.id(),
                dashboard.ownerUserId(), dashboard.accountId());
    }

    @Override
    public Draft draft(UUID id) {
        return jdbc.queryForObject("SELECT * FROM dashboard_drafts WHERE dashboard_id=?", (row, index) ->
                new Draft(row.getObject("dashboard_id", UUID.class), row.getObject("author_user_id", UUID.class),
                        row.getInt("base_revision"), row.getObject("etag", UUID.class),
                        decode(row.getString("definition"), DashboardDefinition.class), instant(row, "updated_at")), id);
    }

    @Override
    public void saveDraft(Draft draft) {
        jdbc.update("""
                INSERT INTO dashboard_drafts(dashboard_id,author_user_id,base_revision,etag,definition,updated_at)
                VALUES(?,?,?,?,?::jsonb,?) ON CONFLICT(dashboard_id) DO UPDATE SET
                author_user_id=EXCLUDED.author_user_id,base_revision=EXCLUDED.base_revision,
                etag=EXCLUDED.etag,definition=EXCLUDED.definition,updated_at=EXCLUDED.updated_at
                """, draft.dashboardId(), draft.authorUserId(), draft.baseRevision(), draft.etag(),
                encode(draft.definition()), Timestamp.from(draft.updatedAt()));
    }

    @Override
    public void appendRevision(Revision revision) {
        jdbc.update("""
                INSERT INTO dashboard_revisions(dashboard_id,revision,definition,published_by,published_at)
                VALUES(?,?,?::jsonb,?,?)
                """, revision.dashboardId(), revision.revision(), encode(revision.definition()),
                revision.publishedBy(), Timestamp.from(revision.publishedAt()));
    }

    @Override
    public Optional<Revision> revision(UUID id, int revision) {
        return jdbc.query("SELECT * FROM dashboard_revisions WHERE dashboard_id=? AND revision=?",
                this::revisionRow, id, revision).stream().findFirst();
    }

    @Override
    public List<Revision> revisions(UUID id, int before, int limit) {
        return jdbc.query("SELECT * FROM dashboard_revisions WHERE dashboard_id=? AND revision<? ORDER BY revision DESC LIMIT ?",
                this::revisionRow, id, before, limit);
    }

    @Override
    public Optional<Preferences> preferences(DashboardActor actor) {
        return jdbc.query("SELECT * FROM dashboard_preferences WHERE user_id=? AND account_id=?", (row, index) -> {
            List<UUID> favorites = new ArrayList<>();
            decode(row.getString("favorites"), JsonNode.class).forEach(id -> favorites.add(UUID.fromString(id.asText())));
            return new Preferences(row.getObject("default_dashboard_id", UUID.class), List.copyOf(favorites),
                    decode(row.getString("default_context"), JsonNode.class), row.getObject("etag", UUID.class),
                    instant(row, "updated_at"), row.getString("fallback_reason"));
        }, actor.userId(), actor.accountId()).stream().findFirst();
    }

    @Override
    public void savePreferences(DashboardActor actor, Preferences preferences) {
        jdbc.update("""
                INSERT INTO dashboard_preferences(user_id,account_id,default_dashboard_id,favorites,default_context,etag,updated_at,fallback_reason)
                VALUES(?,?,?,?::jsonb,?::jsonb,?,?,?) ON CONFLICT(user_id,account_id) DO UPDATE SET
                default_dashboard_id=EXCLUDED.default_dashboard_id,favorites=EXCLUDED.favorites,
                default_context=EXCLUDED.default_context,etag=EXCLUDED.etag,updated_at=EXCLUDED.updated_at,fallback_reason=EXCLUDED.fallback_reason
                """, actor.userId(), actor.accountId(), preferences.defaultDashboardId(), encode(preferences.favorites()),
                encode(preferences.defaultContext()), preferences.etag(), Timestamp.from(preferences.updatedAt()), preferences.fallbackReason());
    }

    @Override
    public Optional<Idempotency> idempotency(DashboardActor actor, String route, String keyHash, Instant now) {
        jdbc.update("DELETE FROM dashboard_idempotency WHERE user_id=? AND account_id=? AND expires_at<=?",
                actor.userId(), actor.accountId(), Timestamp.from(now));
        return jdbc.query("SELECT * FROM dashboard_idempotency WHERE user_id=? AND account_id=? AND route=? AND key_hash=?",
                (row, index) -> new Idempotency(row.getString("body_hash"),
                        decode(row.getString("outcome"), JsonNode.class), instant(row, "expires_at")),
                actor.userId(), actor.accountId(), route, keyHash).stream().findFirst();
    }

    @Override
    public void saveIdempotency(DashboardActor actor, String route, String keyHash, Idempotency outcome) {
        jdbc.update("""
                INSERT INTO dashboard_idempotency(user_id,account_id,route,key_hash,body_hash,outcome,expires_at)
                VALUES(?,?,?,?,?,?::jsonb,?)
                """, actor.userId(), actor.accountId(), route, keyHash, outcome.bodyHash(),
                encode(outcome.outcome()), Timestamp.from(outcome.expiresAt()));
    }

    @Override
    public void audit(DashboardActor actor, UUID id, int revision, String action, Instant at) {
        jdbc.update("""
                INSERT INTO dashboard_lifecycle_audit(id,dashboard_id,actor_user_id,account_id,revision,action,occurred_at)
                VALUES(?,?,?,?,?,?,?)
                """, UUID.randomUUID(), id, actor.userId(), actor.accountId(), revision, action, Timestamp.from(at));
    }

    private Dashboard dashboardRow(ResultSet row, int index) throws SQLException {
        return new Dashboard(row.getObject("id", UUID.class), row.getObject("owner_user_id", UUID.class),
                row.getObject("account_id", UUID.class), row.getString("name"), row.getString("description"),
                row.getString("status"), row.getInt("published_revision"), row.getInt("schema_version"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private Revision revisionRow(ResultSet row, int index) throws SQLException {
        return new Revision(row.getObject("dashboard_id", UUID.class), row.getInt("revision"),
                decode(row.getString("definition"), DashboardDefinition.class),
                row.getObject("published_by", UUID.class), instant(row, "published_at"));
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode dashboard state", exception);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode dashboard state", exception);
        }
    }
}
