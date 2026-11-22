package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.collaboration.QuestionArbiter;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuestionArbiterTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final RequirementChanges changes = new RequirementChanges((schema, value) ->
        validator.validate("collab.VclLiteral", value)
    );
    private final QuestionArbiter arbiter = new QuestionArbiter(changes);
    private final UUID plan = UUID.randomUUID(),
        slot = UUID.randomUUID(),
        source = UUID.randomUUID(),
        evidence = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-12T00:00:00Z");

    private JsonNode text(String value) {
        return mapper.createObjectNode().put("type", "TEXT").put("value", value);
    }

    private Requirements.Snapshot requirements() {
        return changes.propose(
            Requirements.Snapshot.empty(plan),
            0,
            UUID.randomUUID(),
            List.of(
                new Requirements.Slot(
                    slot,
                    "/zone",
                    "text-v1",
                    true,
                    Requirements.Status.UNKNOWN,
                    null,
                    Set.of(source),
                    Set.of(),
                    Set.of(),
                    0,
                    Requirements.Origin.MODEL_PROPOSED
                )
            ),
            Set.of(source)
        );
    }

    private Consultations.Task waiting(Requirements.Snapshot state, String value) {
        UUID id = UUID.randomUUID(),
            attempt = UUID.randomUUID();
        var response = mapper.createObjectNode().put("outcome", "NEED_USER");
        var question = response
            .putArray("questions")
            .addObject()
            .put("text", "Какой часовой пояс?");
        question.putArray("slotIds").add(slot.toString());
        question.putArray("evidenceRefs").add(evidence.toString());
        question
            .putArray("choices")
            .addObject()
            .put("choiceId", "zone")
            .put("label", value)
            .putObject("bindings")
            .set(slot.toString(), text(value));
        var request = new Consultations.Request(
            plan,
            id.toString(),
            "schema",
            "1",
            List.of(UUID.randomUUID()),
            state.revision(),
            List.of(),
            "Zone",
            "result-v1"
        );
        var reads = new Consultations.ReadSet(
            Map.of(slot, state.slots().get(slot).revision()),
            Map.of(),
            "authority",
            "digest"
        );
        var assignment = new Consultations.Assignment(
            attempt,
            UUID.randomUUID(),
            state.revision(),
            0,
            0,
            reads,
            now.plusSeconds(240),
            now.plusSeconds(60)
        );
        return new Consultations.Task(
            id,
            request,
            Consultations.Reason.SCHEMA_REASONING,
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
            state.revision()
        );
    }

    private QuestionArbiter.Decision decide(
        Requirements.Snapshot state,
        List<Consultations.Task> tasks
    ) {
        return arbiter.decide(
            state,
            tasks,
            Set.of(evidence),
            UUID.randomUUID(),
            UUID.randomUUID(),
            now,
            now.plusSeconds(120)
        );
    }

    @Test
    void sameSlotQuestionIsMergedAtRootWithOneSetOfChoices() {
        var state = requirements();
        var first = waiting(state, "UTC");
        var second = waiting(state, "UTC");
        var decision = decide(state, List.of(first, second));
        assertThat(decision.taskIds()).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(decision.requirements().question().slots()).containsExactly(slot);
        assertThat(decision.requirements().question().choices()).hasSize(1);
        assertThat(decision.requirements().revision()).isEqualTo(state.revision());
        assertThat(decision.requirements().slots().get(slot).status()).isEqualTo(
            Requirements.Status.UNKNOWN
        );
    }

    @Test
    void ConflictingChoicesAreNotResolvedByMajorityVote() {
        var state = requirements();
        var decision = decide(
            state,
            List.of(waiting(state, "UTC"), waiting(state, "UTC"), waiting(state, "Europe/Belgrade"))
        );
        assertThat(decision.requirements().slots().get(slot).status()).isEqualTo(
            Requirements.Status.CONFLICT
        );
        assertThat(decision.requirements().slots().get(slot).value()).isNull();
        assertThat(decision.requirements().question().choices()).isEmpty();
        assertThat(decision.requirements().question().requirementsRevision()).isEqualTo(
            state.revision() + 1
        );
        assertThat(decision.requirements().events()).hasSize(state.events().size() + 1);
    }

    @Test
    void confirmedOrChangedSlotsCannotBeReaskedFromOldWorkerResult() {
        var state = requirements();
        var waiting = waiting(state, "UTC");
        var confirmed = changes.amend(
            state,
            state.revision(),
            UUID.randomUUID(),
            source,
            Map.of(slot, text("Europe/Belgrade"))
        );
        var decision = decide(confirmed, List.of(waiting));
        assertThat(decision.requirements()).isSameAs(confirmed);
        assertThat(decision.taskIds()).isEmpty();
    }

    @Test
    void revokedEvidenceCannotReachRootQuestion() {
        var state = requirements();
        assertThatThrownBy(() ->
            arbiter.decide(
                state,
                List.of(waiting(state, "UTC")),
                Set.of(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                now,
                now.plusSeconds(120)
            )
        ).hasMessage("QUESTION_EVIDENCE_REVOKED");
    }
}
