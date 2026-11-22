package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.vcl.DurableDurationWitness.Observation;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Context;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Input;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Plan;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore;
import com.voltcanary.vcl.VclException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class KeyedWitnessContract extends KeyedRekeyContract {

    private static final String PINS = "b".repeat(64);
    private static final String SCOPE = BundleFixtures.SCOPE;
    private static final Plan PLAN = plan();

    private KeyedStateStore store() {
        return new JdbcKeyedStateStore(source());
    }

    private DurableDurationWitness machine() {
        return new DurableDurationWitness(store());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(source());
    }

    @Test
    void typedWitnessRestartsFromSqlAndReturnsTheOriginalResultAfterLaterCorrection() {
        var context = context();
        var holder = UUID.randomUUID();
        var first = input(context, 1, 1, 0, true);
        var second = input(context, 2, 1, 2_000_000, true);
        assertEquals(
            ScalarValue.Status.UNKNOWN,
            process(machine(), context, holder, first).result().value().status()
        );
        var positive = process(machine(), context, holder, second);
        assertEquals(ScalarValue.Status.OK, positive.result().value().status());
        assertEquals(2_000_000, positive.result().coveredDurationUs());
        var correction = process(machine(), context, holder, input(context, 1, 2, 0, false));
        assertEquals(ScalarValue.Status.UNKNOWN, correction.result().value().status());
        var replay = process(machine(), context, holder, second);
        assertTrue(replay.receipt().replay());
        assertEquals(positive.receipt().committedRevision(), replay.receipt().committedRevision());
        assertEquals(positive.result().value().literal(), replay.result().value().literal());
        assertEquals(3, store().inspect(context.partition(), PINS).revision());
        assertEquals(3, store().pendingOutputs(context.partition(), PINS, 128).size());
    }

    @Test
    void changedPayloadAtTheSameInputRevisionCannotOverwriteOrReplay() {
        var context = context();
        var holder = UUID.randomUUID();
        process(machine(), context, holder, input(context, 1, 1, 0, true));
        code("KEY_INPUT_CONFLICT", () ->
            process(machine(), context, holder, input(context, 1, 1, 0, false))
        );
        assertEquals(1, store().inspect(context.partition(), PINS).revision());
        assertEquals(1, store().pendingOutputs(context.partition(), PINS, 128).size());
    }

    @Test
    void lostSqlCommitAckRecoversFromActualInputAndOutputReceipts() {
        var context = context();
        var holder = UUID.randomUUID();
        var observation = input(context, 1, 1, 0, true);
        code("KEY_STORAGE_UNAVAILABLE", () ->
            process(
                new DurableDurationWitness(losingWitnessCommitAck()),
                context,
                holder,
                observation
            )
        );
        assertEquals(1, store().inspect(context.partition(), PINS).revision());
        var recovered = process(machine(), context, holder, observation);
        assertTrue(recovered.receipt().replay());
        assertEquals(1, recovered.receipt().committedRevision());
        assertEquals(1, store().pendingOutputs(context.partition(), PINS, 128).size());
    }

    @Test
    void aReceiptCommittedBetweenLookupAndAcquireReturnsItsOriginalResult() {
        var context = context();
        var holder = UUID.randomUUID();
        var observation = input(context, 1, 1, 0, true);
        var once = new AtomicBoolean();
        var actual = store();
        var proxy = (KeyedStateStore) Proxy.newProxyInstance(
            KeyedStateStore.class.getClassLoader(),
            new Class<?>[] { KeyedStateStore.class },
            (ignored, method, args) -> {
                if (method.getName().equals("inputReceipt") && once.compareAndSet(false, true)) {
                    process(machine(), context, holder, observation);
                    return java.util.Optional.empty();
                }
                try {
                    return method.invoke(actual, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        );
        var result = process(new DurableDurationWitness(proxy), context, holder, observation);
        assertTrue(result.receipt().replay());
        assertEquals(1, store().inspect(context.partition(), PINS).revision());
    }

    @Test
    void stateOutputAndInboxRollBackIfEitherFinalWriteFails() {
        for (String table : List.of("vcl_keyed_outbox", "vcl_keyed_inbox")) {
            var context = context();
            var holder = UUID.randomUUID();
            var observation = input(context, 1, 1, 0, true);
            jdbc().execute(
                "ALTER TABLE " +
                    table +
                    " ADD CONSTRAINT vcl_witness_failure CHECK (account_id <> CAST('" +
                    context.partition().accountId() +
                    "' AS UUID))"
            );
            try {
                code("KEY_STORAGE_UNAVAILABLE", () ->
                    process(machine(), context, holder, observation)
                );
                assertEquals(0, store().inspect(context.partition(), PINS).revision());
                assertTrue(store().pendingOutputs(context.partition(), PINS, 128).isEmpty());
                assertTrue(
                    store()
                        .inputReceipt(
                            context.partition(),
                            PINS,
                            KeyedStateStore.InputKind.OBSERVATION,
                            observation.input().observationId(),
                            1
                        )
                        .isEmpty()
                );
            } finally {
                jdbc().execute("ALTER TABLE " + table + " DROP CONSTRAINT vcl_witness_failure");
            }
            assertFalse(process(machine(), context, holder, observation).receipt().replay());
        }
    }

    @Test
    void retainedSampleLimitRejectsNewInputsWithoutEvictionOrAnotherOutput() {
        var context = context();
        var holder = UUID.randomUUID();
        for (int i = 1; i <= DurableDurationWitness.MAX_SAMPLES; i++) process(
            machine(),
            context,
            holder,
            input(context, i, 1, i, false)
        );
        code("WITNESS_STATE_INPUT_LIMIT", () ->
            process(machine(), context, holder, input(context, 1000, 1, 1000, false))
        );
        assertEquals(128, store().inspect(context.partition(), PINS).revision());
        assertEquals(128, store().pendingOutputs(context.partition(), PINS, 128).size());
        assertTrue(
            process(machine(), context, holder, input(context, 1, 1, 1, false))
                .receipt()
                .replay()
        );
    }

    @Test
    void schemaWindowAndScopeCannotBeRetargetedOnAnExistingWitnessKey() {
        var context = context();
        var holder = UUID.randomUUID();
        var observation = input(context, 1, 1, 0, true);
        process(machine(), context, holder, observation);
        var shifted = new Context(
            context.kernelVersion(),
            context.planDigest(),
            context.scopeDigest(),
            context.partition(),
            new VclWindowPlan.Window(Instant.EPOCH, Instant.EPOCH.plusSeconds(20))
        );
        code("KEY_REGISTRATION_CHANGED", () -> process(machine(), shifted, holder, observation));
        code("ACCESS_SCOPE_CHANGED", () ->
            machine().process(PLAN, context, PINS, observation, "e".repeat(64), holder)
        );
        assertEquals(1, store().inspect(context.partition(), PINS).revision());
    }

    @Test
    void invalidFreshScopeOrPartitionCannotRegisterAnyState() {
        var context = context();
        var holder = UUID.randomUUID();
        var observation = input(context, 1, 1, 0, true);
        code("ACCESS_SCOPE_CHANGED", () ->
            machine().process(PLAN, context, PINS, observation, "e".repeat(64), holder)
        );
        var foreign = new KeyedStateStore.Key(
            context.partition().accountId(),
            UUID.randomUUID(),
            context.partition().deploymentEpoch(),
            context.partition().subjectDigest(),
            context.partition().occurrenceDigest()
        );
        var wrongRule = new Context(
            context.kernelVersion(),
            context.planDigest(),
            context.scopeDigest(),
            foreign,
            context.window()
        );
        code("WITNESS_RULE_CHANGED", () -> process(machine(), wrongRule, holder, observation));
        assertEquals(
            0,
            jdbc().queryForObject(
                "SELECT COUNT(*) FROM vcl_keyed_state WHERE account_id=?",
                Integer.class,
                context.partition().accountId()
            )
        );
    }

    @Test
    void unknownGapsStayUnknownAndCorrectionOutputsRemainAppendOnly() {
        var context = context();
        var holder = UUID.randomUUID();
        process(machine(), context, holder, input(context, 1, 1, 0, true));
        var gap = process(machine(), context, holder, input(context, 2, 1, 3_000_000, true));
        assertEquals(ScalarValue.Status.UNKNOWN, gap.result().value().status());
        assertEquals(0, gap.result().coveredDurationUs());
        var corrected = process(machine(), context, holder, input(context, 2, 2, 2_000_000, true));
        assertEquals(ScalarValue.Status.OK, corrected.result().value().status());
        assertEquals(
            ScalarValue.Status.UNKNOWN,
            process(machine(), context, holder, input(context, 2, 1, 3_000_000, true))
                .result()
                .value()
                .status()
        );
        assertEquals(3, store().pendingOutputs(context.partition(), PINS, 128).size());
    }

    @Test
    void inputReceiptHashesDetectCorruptionAndLegacyRowsAreNeverBackfilled() {
        var context = context();
        var holder = UUID.randomUUID();
        var observation = input(context, 1, 1, 0, true);
        process(machine(), context, holder, observation);
        var key = context.partition();
        var receipt = store()
            .inputReceipt(
                key,
                PINS,
                KeyedStateStore.InputKind.OBSERVATION,
                observation.input().observationId(),
                1
            )
            .orElseThrow();
        assertTrue(receipt.inputDigest().matches("[0-9a-f]{64}"));
        jdbc().update(
            "UPDATE vcl_keyed_inbox SET input_digest=? WHERE account_id=? AND key_digest=?",
            PINS,
            key.accountId(),
            key.digest()
        );
        code("KEY_RECEIPT_CORRUPT", () -> process(machine(), context, holder, observation));
        jdbc().update(
            "UPDATE vcl_keyed_inbox SET input_digest=NULL,outputs_digest=NULL,receipt_digest=NULL WHERE account_id=? AND key_digest=?",
            key.accountId(),
            key.digest()
        );
        code("WITNESS_LEGACY_RECEIPT_UNAVAILABLE", () ->
            process(machine(), context, holder, observation)
        );
        assertNull(
            store()
                .inputReceipt(
                    key,
                    PINS,
                    KeyedStateStore.InputKind.OBSERVATION,
                    observation.input().observationId(),
                    1
                )
                .orElseThrow()
                .inputDigest()
        );
        assertEquals(1, store().inspect(key, PINS).revision());
    }

    @Test
    void changedResultPayloadCannotMatchTheOriginalInputReceipt() {
        var context = context();
        var holder = UUID.randomUUID();
        var observation = input(context, 1, 1, 0, true);
        process(machine(), context, holder, observation);
        var output = store().pendingOutputs(context.partition(), PINS, 1).getFirst().output();
        var value = BundleFixtures.PARSER.readJson(
            output.payload().payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        ((ObjectNode) value).put("coveredDurationUs", "1");
        var changed = new KeyedStateStore.Output(
            output.id(),
            output.kind(),
            null,
            KeyedStateStore.State.parse(value.toString())
        );
        jdbc().update(
            "UPDATE vcl_keyed_outbox SET payload=?,payload_digest=?,output_digest=? WHERE account_id=? AND key_digest=? AND output_id=?",
            changed.payload().payload(),
            changed.payload().digest(),
            changed.digest(),
            context.partition().accountId(),
            context.partition().digest(),
            output.id()
        );
        code("WITNESS_OUTPUT_CHANGED", () -> process(machine(), context, holder, observation));
    }

    @Test
    void inputReceiptInspectionKeepsObservationAndTimerNamespacesSeparate() {
        var key = context().partition();
        var actual = store();
        actual.register(key, PINS, KeyedStateStore.State.parse("{}"));
        var lease = actual.acquire(key, PINS, UUID.randomUUID());
        UUID id = UUID.randomUUID();
        var transition = new KeyedStateStore.Transition(
            id,
            1,
            PINS,
            0,
            KeyedStateStore.State.parse("{}")
        );
        actual.commit(key, lease, transition);
        assertTrue(
            actual.inputReceipt(key, PINS, KeyedStateStore.InputKind.OBSERVATION, id, 1).isPresent()
        );
        assertTrue(
            actual.inputReceipt(key, PINS, KeyedStateStore.InputKind.TIMER, id, 1).isEmpty()
        );
        code("KEY_PINS_CHANGED", () ->
            actual.inputReceipt(key, SCOPE, KeyedStateStore.InputKind.OBSERVATION, id, 1)
        );
        jdbc().update(
            "UPDATE vcl_keyed_inbox SET input_digest=NULL,outputs_digest=NULL,receipt_digest=NULL WHERE account_id=? AND key_digest=?",
            key.accountId(),
            key.digest()
        );
        assertTrue(actual.commit(key, lease, transition).replay());
    }

    @Test
    void v039UpgradePreservesActualLegacyRowsAndEnrichesOnlyNewReceipts() {
        String schema = "vcl_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc().execute("CREATE SCHEMA " + schema);
        var upgradeSource = new WrappedSource(source()) {
            @Override
            Connection wrap(Connection connection) throws SQLException {
                connection.setSchema(schema);
                return connection;
            }
        };
        try {
            var oldMigrations =
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
            for (String name : List.of(
                "V035__vcl_keyed_state.sql",
                "V036__vcl_keyed_timers.sql",
                "V037__vcl_keyed_outbox.sql",
                "V038__vcl_keyed_rekey_receipts.sql"
            )) {
                oldMigrations.addScript(
                    new org.springframework.core.io.ClassPathResource("db/migration/" + name)
                );
            }
            oldMigrations.execute(upgradeSource);
            var sql = new JdbcTemplate(upgradeSource);
            var key = context().partition();
            var initial = KeyedStateStore.State.parse("{}");
            UUID input = UUID.randomUUID();
            var transition = new KeyedStateStore.Transition(input, 1, PINS, 0, initial);
            String registration = com.voltcanary.vcl.VclCanonical.sha256(
                KeyedStateStore.VERSION +
                    "\n" +
                    key.digest() +
                    "\n" +
                    PINS +
                    "\n" +
                    initial.digest()
            );
            sql.update(
                """
                INSERT INTO vcl_keyed_state(account_id,key_digest,rule_id,deployment_epoch,subject_digest,occurrence_digest,
                state_version,registration_digest,pins_digest,state_revision,state_digest,state_payload,inbox_count,lease_fence)
                VALUES(?,?,?,?,?,?,?,?,?,1,?,?,1,0)
                """,
                key.accountId(),
                key.digest(),
                key.ruleId(),
                key.deploymentEpoch(),
                key.subjectDigest(),
                key.occurrenceDigest(),
                KeyedStateStore.VERSION,
                registration,
                PINS,
                initial.digest(),
                initial.payload()
            );
            sql.update(
                "INSERT INTO vcl_keyed_inbox(account_id,key_digest,input_id,input_revision,input_kind,transition_digest,committed_revision) VALUES(?,?,?,1,'OBSERVATION',?,1)",
                key.accountId(),
                key.digest(),
                input,
                transition.digest()
            );
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource(
                    "db/migration/V039__vcl_keyed_input_receipt_digests.sql"
                )
            ).execute(upgradeSource);
            var upgraded = new JdbcKeyedStateStore(upgradeSource);
            var receipt = upgraded
                .inputReceipt(key, PINS, KeyedStateStore.InputKind.OBSERVATION, input, 1)
                .orElseThrow();
            assertNull(receipt.inputDigest());
            assertNull(receipt.outputsDigest());
            assertEquals(transition.digest(), receipt.transitionDigest());
            var lease = upgraded.acquire(key, PINS, UUID.randomUUID());
            assertTrue(upgraded.commit(key, lease, transition).replay());
            UUID fresh = UUID.randomUUID();
            upgraded.commit(key, lease, new KeyedStateStore.Transition(fresh, 1, PINS, 1, initial));
            assertEquals(
                PINS,
                upgraded
                    .inputReceipt(key, PINS, KeyedStateStore.InputKind.OBSERVATION, fresh, 1)
                    .orElseThrow()
                    .inputDigest()
            );
            assertNull(
                upgraded
                    .inputReceipt(key, PINS, KeyedStateStore.InputKind.OBSERVATION, input, 1)
                    .orElseThrow()
                    .inputDigest()
            );
            assertEquals(
                2,
                sql.queryForObject("SELECT COUNT(*) FROM vcl_keyed_inbox", Integer.class)
            );
        } finally {
            jdbc().execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private KeyedStateStore losingWitnessCommitAck() {
        var once = new AtomicBoolean();
        return new JdbcKeyedStateStore(
            new WrappedSource(source()) {
                @Override
                Connection wrap(Connection connection) {
                    var changed = new AtomicBoolean();
                    return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (ignored, method, args) -> {
                            try {
                                var result = method.invoke(connection, args);
                                if (
                                    method.getName().equals("prepareStatement") &&
                                    ((String) args[0]).contains("INSERT INTO vcl_keyed_inbox")
                                ) {
                                    var statement = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] { PreparedStatement.class },
                                        (proxy, call, values) -> {
                                            try {
                                                var returned = call.invoke(statement, values);
                                                if (
                                                    call.getName().equals("executeUpdate")
                                                ) changed.set(true);
                                                return returned;
                                            } catch (InvocationTargetException failure) {
                                                throw failure.getCause();
                                            }
                                        }
                                    );
                                }
                                if (
                                    method.getName().equals("commit") &&
                                    changed.get() &&
                                    once.compareAndSet(false, true)
                                ) throw new SQLException("lost acknowledgement", "08006");
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }
                    );
                }
            }
        );
    }

    private static Plan plan() {
        var document = BundleFixtures.document("durable witness").json();
        for (var node : document.at("/spec/nodes"))
            if (node.path("id").asText().equals("action")) ((ObjectNode) node.path("config")).put(
                "minimumDurationUs",
                "2000000"
            );
        var compiled = BundleFixtures.COMPILER.compile(
            BundleFixtures.PARSER.parse(document.toString()),
            BundleFixtures.METADATA
        );
        return Plan.compile(
            compiled,
            "action",
            new VclDurationWitnessKernel.HoldContract("bounded-sample-hold-1", 2_000_000)
        );
    }

    private static Context context() {
        var key = new KeyedStateStore.Key(
            UUID.randomUUID(),
            PLAN.ruleId(),
            UUID.randomUUID(),
            "c".repeat(64),
            "d".repeat(64)
        );
        return new Context(
            VclDurationWitnessKernel.VERSION,
            PLAN.digest(),
            SCOPE,
            key,
            new VclWindowPlan.Window(Instant.EPOCH, Instant.EPOCH.plusSeconds(10))
        );
    }

    private static Observation input(
        Context context,
        long id,
        long revision,
        long us,
        boolean value
    ) {
        var predicate = ScalarValue.known(
            BundleFixtures.PARSER,
            "{\"type\":\"BOOLEAN\",\"value\":" + value + "}"
        );
        return new Observation(
            revision,
            new Input(
                new UUID(0, id),
                context.partition(),
                Instant.EPOCH.plusNanos(us * 1000),
                predicate
            )
        );
    }

    private static DurableDurationWitness.Outcome process(
        DurableDurationWitness machine,
        Context context,
        UUID holder,
        Observation observation
    ) {
        return machine.process(PLAN, context, PINS, observation, SCOPE, holder);
    }

    private static void code(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(VclException.class, action).code());
    }
}
