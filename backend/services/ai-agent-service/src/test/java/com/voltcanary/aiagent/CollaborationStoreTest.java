package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.aiagent.adapter.JdbcCollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementAnswers;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CollaborationStoreTest {

    record Fixture(
        IntentScenarioSupport f,
        JdbcCollaborationStore store,
        IntentPlans.Token turn,
        UUID slot
    ) {}

    static Fixture fixture(IntentScenarioSupport f) {
        return fixture(f, "{}");
    }

    static Fixture fixture(IntentScenarioSupport f, String requestJson) {
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V011__assistant_collaboration.sql")
        ).execute(f.jdbc.getDataSource());
        var seed = new IntentPlans.State(
            UUID.randomUUID(),
            f.conversation,
            0,
            "PLANNING",
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            "policy",
            "1",
            f.now.plusSeconds(1800),
            List.of()
        );
        var claim = f.store.begin(
            f.session.owner(),
            seed,
            UUID.randomUUID(),
            0,
            "TURN",
            requestJson,
            "Describe requirements",
            false
        );
        f.jdbc.update(
            "update ai_orchestration_requests set lease_until=? where plan_id=?",
            Timestamp.from(f.now.plusSeconds(120)),
            seed.planId()
        );
        f.jdbc.update(
            "update ai_conversation_requests set lease_until=? where plan_id=?",
            Timestamp.from(f.now.plusSeconds(120)),
            seed.planId()
        );
        return new Fixture(
            f,
            new JdbcCollaborationStore(f.jdbc, f.transactions, f.mapper),
            claim.token(),
            UUID.randomUUID()
        );
    }

    static CollaborationStore.State propose(Fixture fixture, CollaborationStore.State before) {
        var changes = new RequirementChanges((schema, value) ->
            fixture.f.validator.validate("collab.VclLiteral", value)
        );
        var slot = new Requirements.Slot(
            fixture.slot,
            "/name",
            "text-v1",
            true,
            Requirements.Status.UNKNOWN,
            null,
            Set.of(fixture.turn.messageId()),
            Set.of(),
            Set.of(),
            0,
            Requirements.Origin.MODEL_PROPOSED
        );
        var requirements = changes.propose(
            before.requirements(),
            before.requirements().revision(),
            UUID.randomUUID(),
            List.of(slot),
            Set.of(fixture.turn.messageId())
        );
        return new CollaborationStore.State(
            before.revision() + 1,
            requirements,
            before.consultations()
        );
    }

    @Test
    void requirementQuestionAndReviewInvalidationShareOneCommit() throws Exception {
        verifyAtomicRequirements(fixture(new IntentScenarioSupport()));
    }

    @Test
    void ownerCancelsWithoutClaimingAnotherRootTurn() throws Exception {
        verifyCancellation(fixture(new IntentScenarioSupport()));
    }

    @Test
    void graphQuestionBecomesAnAnswerableRequirement() throws Exception {
        verifyQuestionPublication(fixture(new IntentScenarioSupport()));
    }

    @Test
    void specialistQuestionAndRootPauseCommitTogether() throws Exception {
        var fixture = fixture(new IntentScenarioSupport());
        var f = fixture.f;
        var turn = fixture.turn;
        var store = new com.voltcanary.aiagent.adapter.JdbcIntentPlanStore(
            f.jdbc,
            f.transactions,
            f.mapper,
            f.validator,
            f.clock,
            java.time.Duration.ofSeconds(30),
            fixture.store
        );
        var slot = new Requirements.Slot(
            fixture.slot,
            "/steps/dashboard/questions/source/name",
            "text-v1",
            true,
            Requirements.Status.UNKNOWN,
            null,
            Set.of(turn.messageId()),
            Set.of(),
            Set.of(),
            0,
            Requirements.Origin.MODEL_PROPOSED
        );
        var changes = new RequirementChanges((schema, value) ->
            f.validator.validate("collab.VclLiteral", value)
        );
        fixture.store.update(turn, 0, "wait", SkillRegistry.digest("wait"), (before, now) -> {
            var requirements = changes.propose(
                before.requirements(),
                0,
                turn.requestId(),
                List.of(slot),
                Set.of(turn.messageId())
            );
            var response = f.mapper.createObjectNode().put("outcome", "NEED_USER");
            response
                .putArray("questions")
                .addObject()
                .put("text", "Which name?")
                .putArray("slotIds")
                .add(slot.id().toString());
            var reads = new Consultations.ReadSet(
                Map.of(slot.id(), 0L),
                Map.of(),
                "authority",
                "digest"
            );
            var assignment = new Consultations.Assignment(
                UUID.randomUUID(),
                turn.requestId(),
                requirements.revision(),
                0,
                0,
                reads,
                now.plusSeconds(120),
                now.plusSeconds(60)
            );
            var request = new Consultations.Request(
                turn.planId(),
                "name",
                "name-assessment",
                "1",
                List.of(UUID.randomUUID()),
                requirements.revision(),
                List.of(),
                "Assess name",
                "name-assessment-v1"
            );
            var task = new Consultations.Task(
                UUID.randomUUID(),
                request,
                Consultations.Reason.INDEPENDENT_ASSESSMENT,
                "request",
                "obligation",
                2,
                Consultations.Status.WAITING_USER,
                1,
                2,
                0,
                0,
                assignment,
                List.of(),
                response,
                requirements.revision()
            );
            return new CollaborationStore.State(
                1,
                requirements,
                before.consultations().replace(task)
            );
        });
        var current = store.read(turn.owner(), turn.planId());
        var step = new AssistantProtocol.StepState(
            "dashboard",
            "CREATE_DASHBOARD",
            "2",
            "1",
            0L,
            List.of(),
            List.of(),
            List.of("goal"),
            "PREPARING",
            null,
            null,
            null
        );
        var goal = new AssistantProtocol.Goal(
            "goal",
            turn.messageId().toString(),
            "Create dashboard",
            "PENDING",
            null
        );
        var preparing = current.revise(
            current.revision(),
            "ACTIVE",
            List.of(step),
            List.of(goal),
            List.of(),
            null,
            new AssistantProtocol.Focus(null, null, null, null, "NONE"),
            Map.of(),
            Map.of(),
            Map.of()
        );
        var saved = store.commit(turn, preparing, "Preparing", true);
        var question = fixture.store.read(turn.owner(), turn.planId()).requirements().question();
        assertThat(saved.pendingQuestion().questionId()).isEqualTo(question.id().toString());
        assertThat(saved.step("dashboard").status()).isEqualTo("WAITING_INPUT");
        assertThat(question.slots()).containsExactly(slot.id());
    }

    static void verifyQuestionPublication(Fixture fixture) {
        var f = fixture.f;
        var turn = fixture.turn;
        var store = new com.voltcanary.aiagent.adapter.JdbcIntentPlanStore(
            f.jdbc,
            f.transactions,
            f.mapper,
            f.validator,
            f.clock,
            java.time.Duration.ofSeconds(30),
            fixture.store
        );
        var current = store.read(turn.owner(), turn.planId());
        UUID id = UUID.randomUUID();
        var question = new AssistantProtocol.PendingQuestion(
            id.toString(),
            com.voltcanary.aiagent.adapter.JdbcIntentPlanStore.stable(
                turn.requestId(),
                "REPLY"
            ).toString(),
            "dashboard",
            current.revision() + 1,
            new AssistantProtocol.Question("Which name?", List.of("name"), List.of())
        );
        var step = new AssistantProtocol.StepState(
            "dashboard",
            "CREATE_DASHBOARD",
            "1",
            "1",
            0L,
            List.of(),
            List.of(),
            List.of("goal"),
            "WAITING_INPUT",
            null,
            null,
            null
        );
        var goal = new AssistantProtocol.Goal(
            "goal",
            turn.messageId().toString(),
            "Create dashboard",
            "PENDING",
            null
        );
        var waiting = current.revise(
            current.revision(),
            "WAITING_INPUT",
            List.of(step),
            List.of(goal),
            List.of(),
            question,
            new AssistantProtocol.Focus(null, null, null, null, "NONE"),
            Map.of(),
            Map.of(),
            Map.of()
        );
        var saved = store.commit(turn, waiting, "Which name?", true);
        assertThat(saved.pendingQuestion()).isEqualTo(question);
        var snapshot = fixture.store.read(turn.owner(), turn.planId()).requirements();
        assertThat(snapshot.question().id()).isEqualTo(id);
        assertThat(snapshot.slots().get(id).sourceMessages()).containsExactly(turn.messageId());
        var body = f.mapper
            .createObjectNode()
            .put("planId", turn.planId().toString())
            .put("questionId", id.toString())
            .put("requestId", UUID.randomUUID().toString())
            .put("expectedQuestionRevision", 0)
            .put("expectedRequirementsRevision", snapshot.revision());
        body.putObject("answer").put("kind", "TEXT").put("text", "New name");
        var answers = new RequirementAnswers(
            f.validator,
            new RequirementChanges((schema, value) ->
                f.validator.validate("collab.VclLiteral", value)
            )
        );
        var answered = store.submitRequirementAnswer(
            fixture.store,
            answers,
            turn.owner(),
            turn.planId(),
            body
        );
        assertThat(answered.pendingQuestion()).isNull();
        assertThat(answered.step("dashboard").status()).isEqualTo("READY");
        assertThat(answered.step("dashboard").inputs()).containsExactly(
            new AssistantProtocol.Input(
                "name",
                new AssistantProtocol.BindingLiteral(
                    "literal",
                    f.mapper.getNodeFactory().textNode("New name")
                )
            )
        );
        assertThat(
            fixture.store
                .read(turn.owner(), turn.planId())
                .requirements()
                .slots()
                .get(id)
                .value()
                .path("value")
                .asText()
        ).isEqualTo("New name");
    }

    static void verifyCancellation(Fixture fixture) {
        var f = fixture.f;
        var turn = fixture.turn;
        var id = UUID.randomUUID();
        var request = new com.voltcanary.aiagent.orchestration.collaboration.Consultations.Request(
            turn.planId(),
            "assess",
            "schema",
            "1",
            List.of(UUID.randomUUID()),
            0,
            List.of(),
            "Assess",
            "result-v1"
        );
        var task = new com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task(
            id,
            request,
            com.voltcanary.aiagent.orchestration.collaboration.Consultations.Reason.SCHEMA_REASONING,
            "request",
            "obligation",
            2,
            com.voltcanary.aiagent.orchestration.collaboration.Consultations.Status.QUEUED,
            0,
            0,
            0,
            0,
            null,
            List.of(),
            null,
            null
        );
        fixture.store.update(turn, 0, "seed", SkillRegistry.digest("seed"), (before, now) ->
            new CollaborationStore.State(
                1,
                before.requirements(),
                before.consultations().replace(task)
            )
        );
        assertThatThrownBy(() ->
            fixture.store.cancel(
                new Scope(UUID.randomUUID(), turn.owner().accountId()),
                turn.planId(),
                id,
                0,
                "stop"
            )
        ).hasMessage("PLAN_UNAVAILABLE");
        var result = fixture.store.cancel(turn.owner(), turn.planId(), id, 0, "stop");
        assertThat(result.status().name()).isEqualTo("CANCELLED");
        assertThat(result.cancellationEpoch()).isEqualTo(1);
        assertThat(fixture.store.cancel(turn.owner(), turn.planId(), id, 0, "stop")).isEqualTo(
            result
        );
        assertThatThrownBy(() ->
            fixture.store.cancel(turn.owner(), turn.planId(), id, 1, "stop")
        ).hasMessage("COLLABORATION_REQUEST_KEY_CONFLICT");
        assertThat(f.store.read(turn.owner(), turn.planId()).revision()).isEqualTo(turn.revision());
    }

    static void verifyAtomicRequirements(Fixture fixture) {
        var f = fixture.f;
        var initial = f.store.read(fixture.turn.owner(), fixture.turn.planId());
        var input = new IntentPlans.ModelInput(
            fixture.turn,
            initial,
            Map.of(),
            new IntentPlans.SourceMessage(fixture.turn.messageId(), "Describe requirements")
        );
        var manifest = f.registry.manifest("CREATE_DEVICE", f.skills.authorized(), Set.of());
        var prepared = f.compile(
            input,
            manifest,
            new AssistantProtocol.Command(
                manifest.commandSchemaId(),
                manifest.skillVersion(),
                f.mapper.createObjectNode().put("name", "Old name")
            ),
            f.session
        );
        var review = prepared.review();
        var step = new AssistantProtocol.StepState(
            "device",
            "CREATE_DEVICE",
            "1",
            "1",
            0L,
            List.of(),
            List.of(),
            List.of("goal"),
            "WAITING_CONFIRMATION",
            review.reviewId().toString(),
            null,
            null
        );
        var goal = new AssistantProtocol.Goal(
            "goal",
            fixture.turn.messageId().toString(),
            "Create device",
            "PENDING",
            null
        );
        var ready = f.store.commit(
            fixture.turn,
            initial.revise(
                initial.revision(),
                "WAITING_CONFIRMATION",
                List.of(step),
                List.of(goal),
                List.of(),
                null,
                new AssistantProtocol.Focus(null, null, null, null, "NONE"),
                Map.of(),
                Map.of("device", review),
                Map.of()
            ),
            "Review ready",
            true
        );
        assertThat(f.reviews.find(review.reviewId(), f.session.owner()).status()).isEqualTo(
            "READY"
        );
        var claim = f.store.begin(
            f.session.owner(),
            ready,
            UUID.randomUUID(),
            ready.revision(),
            "TURN",
            "{}",
            "Update requirements",
            false
        );
        var active = new Fixture(f, fixture.store, claim.token(), fixture.slot);
        UUID questionId = UUID.randomUUID();
        CollaborationStore.Mutation mutation = (before, now) -> {
            var next = propose(active, before);
            var question = new Requirements.Question(
                questionId,
                0,
                next.requirements().revision(),
                List.of(fixture.slot),
                "Which name?",
                List.of(),
                Set.of(),
                now.plusSeconds(60)
            );
            var changes = new RequirementChanges((schema, value) ->
                f.validator.validate("collab.VclLiteral", value)
            );
            return new CollaborationStore.State(
                next.revision(),
                changes.question(next.requirements(), question, now),
                next.consultations()
            );
        };
        String digest = SkillRegistry.digest("atomic requirements");
        f.jdbc.execute(
            "alter table ai_orchestration_collaboration_events add constraint reject_atomic check (request_key <> 'requirements:reject')"
        );
        assertThatThrownBy(() ->
            f.store.commitRequirements(
                fixture.store,
                claim.token(),
                0,
                "reject",
                digest,
                mutation,
                Set.of("device"),
                "Updated"
            )
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(f.store.read(f.session.owner(), ready.planId())).isEqualTo(claim.state());
        assertThat(f.reviews.find(review.reviewId(), f.session.owner()).status()).isEqualTo(
            "READY"
        );
        assertThat(fixture.store.read(f.session.owner(), ready.planId()).revision()).isZero();
        var saved = f.store.commitRequirements(
            fixture.store,
            claim.token(),
            0,
            "accept",
            digest,
            mutation,
            Set.of("device"),
            "Updated"
        );
        assertThat(saved.revision()).isEqualTo(claim.state().revision() + 1);
        assertThat(saved.pendingQuestion().questionId()).isEqualTo(questionId.toString());
        assertThat(saved.step("device").status()).isEqualTo("WAITING_INPUT");
        assertThat(saved.reviews()).isEmpty();
        assertThat(f.reviews.find(review.reviewId(), f.session.owner()).status()).isEqualTo(
            "SUPERSEDED"
        );
        assertThat(
            fixture.store.read(f.session.owner(), ready.planId()).requirements().question().id()
        ).isEqualTo(questionId);
        assertThat(
            f.store.commitRequirements(
                fixture.store,
                claim.token(),
                0,
                "accept",
                digest,
                (before, now) -> {
                    throw new AssertionError("Completed requirements turn must not run again");
                },
                Set.of("device"),
                "Updated"
            )
        ).isEqualTo(saved);
        assertThatThrownBy(() ->
            f.store.commit(claim.token(), claim.state(), "Late", true)
        ).hasMessage("LATE_OR_STALE_RESULT");
        var answerRequest = UUID.randomUUID();
        var body = f.mapper
            .createObjectNode()
            .put("planId", ready.planId().toString())
            .put("questionId", questionId.toString())
            .put("requestId", answerRequest.toString())
            .put("expectedQuestionRevision", 0)
            .put("expectedRequirementsRevision", 1);
        body.putObject("answer").put("kind", "TEXT").put("text", "Exact new name");
        var answers = new RequirementAnswers(
            f.validator,
            new RequirementChanges((schema, value) ->
                f.validator.validate("collab.VclLiteral", value)
            )
        );
        var stale = body.deepCopy().put("expectedQuestionRevision", 42);
        assertThatThrownBy(() ->
            f.store.submitRequirementAnswer(
                fixture.store,
                answers,
                f.session.owner(),
                ready.planId(),
                stale
            )
        ).hasMessage("QUESTION_CHANGED");
        assertThat(f.store.read(f.session.owner(), ready.planId())).isEqualTo(saved);
        assertThat(
            f.jdbc.queryForObject(
                "select count(*) from ai_orchestration_requests where request_id=?",
                Long.class,
                answerRequest
            )
        ).isZero();
        var answered = f.store.submitRequirementAnswer(
            fixture.store,
            answers,
            f.session.owner(),
            ready.planId(),
            body
        );
        assertThat(
            f.store.submitRequirementAnswer(
                fixture.store,
                answers,
                f.session.owner(),
                ready.planId(),
                body
            )
        ).isEqualTo(answered);
        assertThatThrownBy(() ->
            f.store.submitRequirementAnswer(
                fixture.store,
                answers,
                f.session.owner(),
                ready.planId(),
                stale
            )
        ).hasMessage("REQUEST_ID_CONFLICT");
        assertThat(answered.pendingQuestion()).isNull();
        var confirmed = fixture.store
            .read(f.session.owner(), ready.planId())
            .requirements()
            .slots()
            .get(fixture.slot);
        assertThat(confirmed.origin()).isEqualTo(Requirements.Origin.USER_TEXT);
        assertThat(confirmed.sourceMessages()).containsExactly(
            com.voltcanary.aiagent.adapter.JdbcIntentPlanStore.stable(answerRequest, "INPUT")
        );
        assertThat(confirmed.value().get("value").textValue()).isEqualTo("Exact new name");
    }

    @Test
    void jsonHistoryAndSnapshotCommitTogetherAndReplayDoesNotRunMutationAgain() throws Exception {
        verifyRestartAndReplay(fixture(new IntentScenarioSupport()));
    }

    static void verifyRestartAndReplay(Fixture fixture) {
        var count = new AtomicInteger();
        var next = fixture.store.update(
            fixture.turn,
            0,
            "propose",
            SkillRegistry.digest("proposal"),
            (state, now) -> {
                count.incrementAndGet();
                assertThat(now).isAfter(fixture.f.now.minusSeconds(5));
                return propose(fixture, state);
            }
        );
        var restarted = new JdbcCollaborationStore(
            fixture.f.jdbc,
            fixture.f.transactions,
            fixture.f.mapper
        );
        assertThat(restarted.read(fixture.turn.owner(), fixture.turn.planId())).isEqualTo(next);
        assertThat(
            restarted.update(
                fixture.turn,
                0,
                "propose",
                SkillRegistry.digest("proposal"),
                (state, now) -> {
                    throw new AssertionError("Replay must not run mutation");
                }
            )
        ).isEqualTo(next);
        assertThat(count).hasValue(1);
        assertThatThrownBy(() ->
            restarted.update(
                fixture.turn,
                0,
                "propose",
                SkillRegistry.digest("different"),
                (state, now) -> state
            )
        ).hasMessage("COLLABORATION_REQUEST_KEY_CONFLICT");
        assertThatThrownBy(() ->
            restarted.update(
                fixture.turn,
                0,
                "new",
                SkillRegistry.digest("new"),
                (state, now) -> state
            )
        ).hasMessage("COLLABORATION_REVISION_CHANGED");
        assertThat(
            fixture.f.jdbc.queryForObject(
                "select count(*) from ai_orchestration_collaboration_events",
                Long.class
            )
        ).isEqualTo(1);
        String historical = fixture.f.jdbc.queryForObject(
            "select state_json from ai_orchestration_collaboration_events",
            String.class
        );
        assertThat(historical).contains(fixture.slot.toString());
    }

    @Test
    void failedJournalWriteRollsBackSnapshotAsWell() throws Exception {
        verifyRollback(fixture(new IntentScenarioSupport()));
    }

    static void verifyRollback(Fixture fixture) {
        fixture.f.jdbc.execute(
            "alter table ai_orchestration_collaboration_events add constraint reject_fixture check (request_key <> 'reject')"
        );
        assertThatThrownBy(() ->
            fixture.store.update(
                fixture.turn,
                0,
                "reject",
                SkillRegistry.digest("proposal"),
                (state, now) -> propose(fixture, state)
            )
        ).isInstanceOf(RuntimeException.class);
        assertThat(
            fixture.f.jdbc.queryForObject(
                "select count(*) from ai_orchestration_collaboration",
                Long.class
            )
        ).isZero();
        assertThat(
            fixture.f.jdbc.queryForObject(
                "select count(*) from ai_orchestration_collaboration_events",
                Long.class
            )
        ).isZero();
    }

    @Test
    void oldRootTokenAndForeignOwnerCannotWriteOrRead() throws Exception {
        verifyFences(fixture(new IntentScenarioSupport()));
    }

    static void verifyFences(Fixture fixture) {
        assertThatThrownBy(() ->
            fixture.store.read(
                new Scope(UUID.randomUUID(), UUID.randomUUID()),
                fixture.turn.planId()
            )
        ).hasMessage("PLAN_UNAVAILABLE");
        fixture.f.jdbc.update(
            "update ai_orchestrations set revision=revision+1 where id=?",
            fixture.turn.planId()
        );
        assertThatThrownBy(() ->
            fixture.store.update(
                fixture.turn,
                0,
                "propose",
                SkillRegistry.digest("proposal"),
                (state, now) -> propose(fixture, state)
            )
        ).hasMessage("COLLABORATION_ROOT_TURN_CHANGED_OR_EXPIRED");
        fixture.f.jdbc.update(
            "update ai_orchestrations set revision=revision-1 where id=?",
            fixture.turn.planId()
        );
        fixture.f.jdbc.update(
            "update ai_orchestration_requests set status='UNKNOWN' where plan_id=?",
            fixture.turn.planId()
        );
        assertThatThrownBy(() ->
            fixture.store.update(
                fixture.turn,
                0,
                "propose",
                SkillRegistry.digest("proposal"),
                (state, now) -> propose(fixture, state)
            )
        ).hasMessage("COLLABORATION_ROOT_TURN_CHANGED_OR_EXPIRED");
    }

    @Test
    void expiredLeaseAndConversationAreCheckedUsingDatabaseTime() throws Exception {
        var fixture = fixture(new IntentScenarioSupport());
        fixture.f.jdbc.update(
            "update ai_orchestration_requests set lease_until=? where plan_id=?",
            Timestamp.from(fixture.f.now.minusSeconds(1)),
            fixture.turn.planId()
        );
        assertThatThrownBy(() ->
            fixture.store.update(
                fixture.turn,
                0,
                "propose",
                SkillRegistry.digest("proposal"),
                (state, now) -> propose(fixture, state)
            )
        ).hasMessage("COLLABORATION_ROOT_TURN_CHANGED_OR_EXPIRED");
        fixture.f.jdbc.update(
            "update ai_conversations set expires_at=? where id=?",
            Timestamp.from(fixture.f.now.minusSeconds(1)),
            fixture.turn.conversationId()
        );
        assertThatThrownBy(() ->
            fixture.store.read(fixture.turn.owner(), fixture.turn.planId())
        ).hasMessage("PLAN_UNAVAILABLE");
    }
}
