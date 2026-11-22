package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

abstract class TemporalRuntimeContract {

    abstract DataSource source();

    static void migrate(DataSource source) throws Exception {
        var scripts = new PathMatchingResourcePatternResolver().getResources(
            "classpath:db/migration/V*.sql"
        );
        var migrations = new ResourceDatabasePopulator();
        java.util.Arrays.stream(scripts)
            .filter(resource -> {
                int version = Integer.parseInt(resource.getFilename().substring(1, 4));
                return version >= 34 && version <= 53;
            })
            .sorted(java.util.Comparator.comparing(resource -> resource.getFilename()))
            .forEach(migrations::addScript);
        migrations.execute(source);
    }

    private record Fixture(
        JdbcKeyedStateStore store,
        JdbcRuleDeployments.Deployment deployment,
        KeyedStateStore.Key key,
        UUID generation,
        UUID holder,
        IntervalWindowSchedule.Recipe recipe
    ) {}

    private Fixture fixture() {
        var jdbc = new JdbcTemplate(source());
        UUID account = UUID.randomUUID(),
            epoch = UUID.randomUUID(),
            rule = UUID.randomUUID();
        UUID actor = UUID.randomUUID(),
            bundle = UUID.randomUUID(),
            grant = UUID.randomUUID(),
            generation = UUID.randomUUID();
        String digest = "a".repeat(64);
        var start = Instant.parse("2026-11-01T00:00:00Z");
        jdbc.update(
            "INSERT INTO vcl_compilation_bundles(account_id,bundle_id,bundle_version,payload_digest,canonical_payload) VALUES(?,?,?,?,?)",
            account,
            bundle,
            "test",
            digest,
            "{}"
        );
        jdbc.update(
            """
            INSERT INTO vcl_rule_deployments(account_id,rule_id,deployment_epoch,bundle_id,bundle_digest,grant_id,
                actor_id,state,active_slot,starts_at,start_checkpoint_digest) VALUES(?,?,?,?,?,?,?,'ACTIVE',1,?,?)
            """,
            account,
            rule,
            epoch,
            bundle,
            digest,
            grant,
            actor,
            start.atOffset(ZoneOffset.UTC),
            digest
        );
        var deployment = new JdbcRuleDeployments.Deployment(
            actor,
            account,
            rule,
            epoch,
            bundle,
            digest,
            grant,
            JdbcRuleDeployments.State.ACTIVE,
            0,
            null,
            new JdbcRuleDeployments.CutoverStart(null, start, digest),
            null
        );
        var window = new VclWindowPlan.Window(start.plusSeconds(1), start.plusSeconds(11));
        var key = new KeyedStateStore.Key(
            account,
            rule,
            epoch,
            "b".repeat(64),
            IntervalWindowSchedule.occurrence(window)
        );
        var recipe = new IntervalWindowSchedule.Recipe(
            "aggregate",
            "c".repeat(64),
            generation,
            window,
            window.endExclusive(),
            "{\"type\":\"TEXT\",\"value\":\"asset\"}",
            0,
            null,
            0,
            null
        );
        var store = new JdbcKeyedStateStore(source());
        assertTrue(store.advanceOpeningCheckpoint(deployment, generation, 0, 0, 1, null));
        return new Fixture(store, deployment, key, generation, UUID.randomUUID(), recipe);
    }

    @Test
    void timeoutWaitsForOnePersistedCutAcrossWorkerRestart() {
        var fixture = fixture();
        var store = fixture.store();
        String pins = "d".repeat(64);
        var state = KeyedStateStore.State.parse("{}");
        store.register(fixture.key(), pins, state);
        var lease = store.acquire(fixture.key(), pins, fixture.holder());
        var timer = new KeyedStateStore.Timer(
            UUID.randomUUID(),
            KeyedStateStore.TimerKind.SESSION_TIMEOUT,
            Instant.EPOCH,
            state
        );
        store.commitSession(
            fixture.key(),
            lease,
            new KeyedStateStore.Transition(
                UUID.randomUUID(),
                1,
                pins,
                0,
                state,
                List.of(timer),
                null
            ),
            fixture.recipe().window().startInclusive(),
            fixture.deployment()
        );
        var due = new KeyedStateStore.DueTimer(
            fixture.key(),
            pins,
            new KeyedStateStore.ScheduledTimer(timer, 1)
        );
        assertFalse(
            store.sessionTimeoutReady(fixture.deployment(), due, fixture.generation(), 0, 3)
        );
        lease = store.acquire(fixture.key(), pins, fixture.holder());
        var transition = new KeyedStateStore.Transition(
            timer.id(),
            1,
            timer.digest(),
            1,
            state,
            List.of(),
            new KeyedStateStore.TimerInput(timer.id(), 1)
        );
        var currentLease = lease;
        assertEquals(
            "SESSION_INPUT_PENDING",
            assertThrows(VclException.class, () ->
                store.commitSession(
                    fixture.key(),
                    currentLease,
                    transition,
                    fixture.recipe().window().startInclusive(),
                    fixture.deployment()
                )
            ).code()
        );
        assertTrue(
            store.advanceOpeningCheckpoint(
                fixture.deployment(),
                fixture.generation(),
                0,
                1,
                2,
                null
            )
        );
        assertTrue(
            store.advanceOpeningCheckpoint(
                fixture.deployment(),
                fixture.generation(),
                0,
                2,
                3,
                null
            )
        );
        var restarted = new JdbcKeyedStateStore(source());
        assertTrue(
            restarted.sessionTimeoutReady(fixture.deployment(), due, fixture.generation(), 0, 20)
        );
        assertEquals(
            2,
            restarted
                .commitSession(
                    fixture.key(),
                    lease,
                    transition,
                    fixture.recipe().window().startInclusive(),
                    fixture.deployment()
                )
                .committedRevision()
        );
        assertTrue(restarted.acquire(fixture.key(), pins, fixture.holder()).timers().isEmpty());
    }

    @Test
    void intervalPrefixScheduleRetainsItsCutAndDueTimeAcrossRestart() {
        var fixture = fixture();
        var base = fixture.recipe();
        var recipe = new IntervalWindowSchedule.Recipe(
            base.node(),
            base.planDigest(),
            base.generation(),
            base.window(),
            base.dueAt().plusSeconds(11),
            base.subjectLiteral(),
            1,
            null,
            0,
            null
        );
        IntervalWindowSchedule.stage(
            fixture.store(),
            fixture.deployment(),
            fixture.key(),
            recipe,
            2,
            fixture.holder(),
            null
        );
        var pins = IntervalWindowSchedule.pins(fixture.key(), fixture.deployment(), recipe);
        var restored = new JdbcKeyedStateStore(source());
        var lease = restored.acquire(fixture.key(), pins, fixture.holder());
        var scheduled = IntervalWindowSchedule.recipe(lease.snapshot().state());
        assertEquals(recipe.advance(2, null), scheduled);
        assertEquals(recipe.window().startInclusive().plusMillis(625), scheduled.measuredThrough());
        var timer = lease.timers().getFirst().timer();
        assertEquals(scheduled.measuredThrough().plusSeconds(11), timer.dueAt());
        assertEquals(scheduled.state(), timer.payload());
        assertEquals(2, scheduled.next(null).prefixIndex());
    }

    @Test
    void pausedOpenVisitClosesOnceAcrossWorkerRestartWithoutEffects() {
        var fixture = fixture();
        var active = fixture.deployment();
        var store = fixture.store();
        var key = fixture.key();
        UUID zone = UUID.randomUUID();
        String scope = "e".repeat(64);
        var centre = new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot.Point(24, 60);
        var circle = new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot.Circle(
            zone,
            1,
            centre,
            java.math.BigDecimal.valueOf(30)
        );
        var geometry = new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot(
            scope,
            java.util.Map.of(zone, circle)
        );
        var plan = new com.voltcanary.analytics.vcl.VclZoneSessionKernel.Plan(
            "f".repeat(64),
            scope,
            active.rule(),
            circle,
            java.math.BigDecimal.ONE,
            true,
            10_000_000,
            2_000_000,
            1_000_000,
            2
        );
        var session = new com.voltcanary.analytics.vcl.DurableZoneSession(
            store,
            plan,
            key,
            active.bundleDigest(),
            () -> {},
            (target, lease, transition, openedAt) ->
                store.commitSession(target, lease, transition, openedAt, active),
            java.util.function.UnaryOperator.identity(),
            () -> geometry
        );
        var at = fixture.recipe().window().startInclusive();
        session.observe(
            new com.voltcanary.analytics.vcl.VclZoneSessionKernel.Observation(
                UUID.randomUUID(),
                at,
                key,
                zone,
                new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot.Point(24.01, 60),
                java.math.BigDecimal.ONE
            ),
            fixture.holder()
        );
        session.observe(
            new com.voltcanary.analytics.vcl.VclZoneSessionKernel.Observation(
                UUID.randomUUID(),
                at.plusSeconds(1),
                key,
                zone,
                centre,
                java.math.BigDecimal.ONE
            ),
            fixture.holder()
        );
        var open = com.voltcanary.analytics.vcl.DurableZoneSession.sessionState(
            key,
            store.inspect(key, session.pinsDigest()).state()
        );
        assertEquals(com.voltcanary.analytics.vcl.VclZoneSessionKernel.Phase.OPEN, open.phase());

        new JdbcTemplate(source()).update(
            """
            UPDATE vcl_rule_deployments SET state='REVOKED',active_slot=NULL,paused_at=CURRENT_TIMESTAMP,revision=1
            WHERE account_id=? AND deployment_epoch=?
            """,
            active.account(),
            active.epoch()
        );
        var paused = new JdbcRuleDeployments.Deployment(
            active.actor(),
            active.account(),
            active.rule(),
            active.epoch(),
            active.bundle(),
            active.bundleDigest(),
            active.grant(),
            JdbcRuleDeployments.State.PAUSED,
            1,
            null,
            active.start(),
            null
        );
        var restarted = new JdbcKeyedStateStore(source());
        var pending = restarted
            .pausedWindows(16, 0)
            .stream()
            .filter(value -> value.key().equals(key))
            .findFirst()
            .orElseThrow();
        restarted.closePausedWindow(pending, paused);
        new JdbcKeyedStateStore(source()).closePausedWindow(pending, paused);
        var saved = restarted.inspect(key, session.pinsDigest());
        var outcome = com.voltcanary.analytics.vcl.DurableZoneSession.sessionState(
            key,
            saved.state()
        );
        assertEquals(
            com.voltcanary.analytics.vcl.VclZoneSessionKernel.Phase.UNKNOWN,
            outcome.phase()
        );
        assertEquals("PAUSED", outcome.reason());
        assertNull(outcome.exitWitness());
        var outputs = restarted.pendingOutputs(key, session.pinsDigest(), 16);
        assertEquals(1, outputs.size());
        assertEquals(KeyedStateStore.OutputKind.MEASURE, outputs.getFirst().output().kind());
        assertTrue(
            restarted
                .pausedWindows(16, 0)
                .stream()
                .noneMatch(value -> value.key().equals(key))
        );
    }

    @Test
    void reviewedCorrectionAndCatchupAreAtomicAndFenceTheOpeningCursor() {
        var fixture = fixture();
        var store = fixture.store();
        String pins = IntervalWindowSchedule.pins(
            fixture.key(),
            fixture.deployment(),
            fixture.recipe()
        );
        store.register(fixture.key(), pins, fixture.recipe().state());
        var lease = store.acquire(fixture.key(), pins, fixture.holder());
        var original = new KeyedStateStore.Output(
            UUID.randomUUID(),
            KeyedStateStore.OutputKind.MEASURE,
            null,
            KeyedStateStore.State.parse("{\"checkpoint\":\"1\"}")
        );
        store.commitReviewedInput(
            fixture.key(),
            lease,
            new KeyedStateStore.Transition(
                UUID.randomUUID(),
                1,
                pins,
                0,
                fixture.recipe().state(),
                List.of(),
                null,
                List.of(original)
            ),
            fixture.recipe().window().startInclusive(),
            fixture.deployment()
        );
        var historical = store.historicalWindow(fixture.deployment(), original.id());
        var outputs = List.of(
            new KeyedStateStore.Output(
                UUID.randomUUID(),
                KeyedStateStore.OutputKind.MEASURE,
                null,
                KeyedStateStore.State.parse("{\"checkpoint\":\"3\"}")
            ),
            new KeyedStateStore.Output(
                UUID.randomUUID(),
                KeyedStateStore.OutputKind.ALERT_PROPOSAL,
                null,
                KeyedStateStore.State.parse("{}")
            )
        );
        UUID correction = UUID.randomUUID();
        new JdbcTemplate(source()).update(
            "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
            Instant.EPOCH.atOffset(ZoneOffset.UTC),
            fixture.key().accountId(),
            fixture.key().digest()
        );
        var catchup = new RuleCompilationSnapshot.CorrectionCatchup(fixture.generation(), 3, 3);
        String payload = "{\"confirmed\":true}",
            digest = VclCanonical.sha256(payload);
        assertEquals(
            outputs.getLast().id(),
            store.appendWindowCorrection(
                fixture.deployment(),
                historical,
                correction,
                digest,
                payload,
                outputs,
                catchup
            )
        );
        assertEquals(1, store.referenceCorrectionRevision(fixture.deployment()));
        assertFalse(
            store.advanceOpeningCheckpoint(fixture.deployment(), fixture.generation(), 0, 1, 2, 0L)
        );
        assertEquals(1, store.openingCheckpoint(fixture.deployment(), fixture.generation(), 0));
        var updated = store.acquire(fixture.key(), pins, correction);
        assertEquals(1, updated.timers().size());
        var scheduled = IntervalWindowSchedule.recipe(updated.snapshot().state());
        assertEquals(outputs.getFirst().id(), scheduled.previousFinal());
        assertEquals(3, scheduled.minimumCheckpoint());
        assertEquals(
            outputs.getLast().id(),
            store.appendWindowCorrection(
                fixture.deployment(),
                historical,
                correction,
                digest,
                payload,
                outputs,
                catchup
            )
        );
        assertEquals(1, store.referenceCorrectionRevision(fixture.deployment()));
        assertEquals(
            1,
            store
                .correctedWindows(
                    fixture.deployment(),
                    fixture.recipe().window().startInclusive(),
                    fixture.recipe().window().endExclusive()
                )
                .size()
        );
    }

    @Test
    void aFailedCorrectionDoesNotLeaveEvidenceOrOutputs() {
        var fixture = fixture();
        var store = fixture.store();
        String pins = IntervalWindowSchedule.pins(
            fixture.key(),
            fixture.deployment(),
            fixture.recipe()
        );
        store.register(fixture.key(), pins, fixture.recipe().state());
        var lease = store.acquire(fixture.key(), pins, fixture.holder());
        var original = new KeyedStateStore.Output(
            UUID.randomUUID(),
            KeyedStateStore.OutputKind.MEASURE,
            null,
            KeyedStateStore.State.parse("{\"checkpoint\":\"1\"}")
        );
        store.commitReviewedInput(
            fixture.key(),
            lease,
            new KeyedStateStore.Transition(
                UUID.randomUUID(),
                1,
                pins,
                0,
                fixture.recipe().state(),
                List.of(),
                null,
                List.of(original)
            ),
            fixture.recipe().window().startInclusive(),
            fixture.deployment()
        );
        var historical = store.historicalWindow(fixture.deployment(), original.id());
        UUID correction = UUID.randomUUID();
        var jdbc = new JdbcTemplate(source());
        jdbc.update(
            "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
            Instant.EPOCH.atOffset(ZoneOffset.UTC),
            fixture.key().accountId(),
            fixture.key().digest()
        );
        // Reject the final ledger link, after the transaction has written state, timer, inbox and both outputs.
        jdbc.execute(
            "ALTER TABLE vcl_reviewed_window_corrections ADD CONSTRAINT fail_correction_" +
                correction.toString().replace("-", "") +
                " CHECK (correction_id <> '" +
                correction +
                "' OR alert_proposal_id IS NULL)"
        );
        var outputs = List.of(
            new KeyedStateStore.Output(
                UUID.randomUUID(),
                KeyedStateStore.OutputKind.MEASURE,
                null,
                KeyedStateStore.State.parse("{\"checkpoint\":\"3\"}")
            ),
            new KeyedStateStore.Output(
                UUID.randomUUID(),
                KeyedStateStore.OutputKind.ALERT_PROPOSAL,
                null,
                KeyedStateStore.State.parse("{}")
            )
        );
        assertThrows(VclException.class, () ->
            store.appendWindowCorrection(
                fixture.deployment(),
                historical,
                correction,
                VclCanonical.sha256("{}"),
                "{}",
                outputs,
                new RuleCompilationSnapshot.CorrectionCatchup(fixture.generation(), 3, 3)
            )
        );
        assertEquals(
            0,
            new JdbcTemplate(source()).queryForObject(
                "SELECT COUNT(*) FROM vcl_reviewed_window_corrections WHERE account_id=?",
                Integer.class,
                fixture.key().accountId()
            )
        );
        assertEquals(1, store.pendingOutputs(fixture.key(), pins, 128).size());
        var after = store.acquire(fixture.key(), pins, correction);
        assertEquals(1, after.snapshot().revision());
        assertTrue(after.timers().isEmpty());
        assertEquals(0, store.referenceCorrectionRevision(fixture.deployment()));
    }
}
