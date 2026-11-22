package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Choice;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Origin;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Question;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Slot;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Snapshot;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Status;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequirementChangesTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final UUID source = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-12T00:00:00Z");
    private final RequirementChanges changes = new RequirementChanges((schema, value) -> {
        validator.validate("collab.VclLiteral", value);
        if (
            !schema.equals("text-v1") || !value.path("type").asText().equals("TEXT")
        ) throw Requirements.invalid("SLOT_TYPE_MISMATCH");
    });

    private JsonNode text(String value) {
        return mapper.createObjectNode().put("type", "TEXT").put("value", value);
    }

    private Slot slot(UUID id, String path, Set<UUID> dependsOn) {
        return new Slot(
            id,
            path,
            "text-v1",
            true,
            Status.UNKNOWN,
            null,
            Set.of(source),
            Set.of(),
            dependsOn,
            0,
            Origin.MODEL_PROPOSED
        );
    }

    private Snapshot initial(List<Slot> slots) {
        return changes.propose(
            Snapshot.empty(UUID.randomUUID()),
            0,
            UUID.randomUUID(),
            slots,
            Set.of(source)
        );
    }

    private Question question(Snapshot state, UUID slot, List<Choice> choices) {
        return new Question(
            UUID.randomUUID(),
            0,
            state.revision(),
            List.of(slot),
            "Уточните значение",
            choices,
            Set.of(),
            now.plusSeconds(60)
        );
    }

    @Test
    void unrelatedEditPreservesIndependentValuesAndInvalidatesOnlyTransitiveDependants() {
        UUID zone = UUID.randomUUID(),
            window = UUID.randomUUID(),
            rule = UUID.randomUUID(),
            label = UUID.randomUUID();
        var state = initial(
            List.of(
                slot(zone, "/zone", Set.of()),
                slot(window, "/window", Set.of(zone)),
                slot(rule, "/rule", Set.of(window)),
                slot(label, "/label", Set.of())
            )
        );
        state = changes.amend(
            state,
            state.revision(),
            UUID.randomUUID(),
            source,
            Map.of(
                zone,
                text("UTC"),
                window,
                text("daily"),
                rule,
                text("bounded"),
                label,
                text("untouched")
            )
        );
        var prior = state;
        var next = changes.amend(
            state,
            state.revision(),
            UUID.randomUUID(),
            source,
            Map.of(zone, text("Europe/Belgrade"))
        );
        assertThat(next.slots().get(zone).status()).isEqualTo(Status.CONFIRMED);
        assertThat(next.slots().get(window).status()).isEqualTo(Status.STALE);
        assertThat(next.slots().get(rule).value()).isNull();
        assertThat(next.slots().get(label)).isEqualTo(prior.slots().get(label));
        assertThat(next.events().getLast().invalidatedSlots()).containsExactlyInAnyOrder(
            window,
            rule
        );
        assertThat(changes.complete(next)).isFalse();
        assertThat(prior.slots().get(window).status()).isEqualTo(Status.CONFIRMED);
    }

    @Test
    void answerChecksBothRevisionsQuestionIdentityExpiryAndExactChoice() {
        UUID id = UUID.randomUUID();
        var base = initial(List.of(slot(id, "/name", Set.of())));
        var q = question(base, id, List.of(new Choice("one", "One", Map.of(id, text("selected")))));
        var state = changes.question(base, q, now);
        assertThatThrownBy(() ->
            changes.answer(
                state,
                q.id(),
                1,
                state.revision(),
                UUID.randomUUID(),
                source,
                Map.of(),
                "one",
                now
            )
        ).hasMessage("QUESTION_CHANGED");
        assertThatThrownBy(() ->
            changes.answer(
                state,
                q.id(),
                0,
                state.revision() - 1,
                UUID.randomUUID(),
                source,
                Map.of(),
                "one",
                now
            )
        ).hasMessage("REQUIREMENTS_REVISION_CHANGED");
        assertThatThrownBy(() ->
            changes.answer(
                state,
                UUID.randomUUID(),
                0,
                state.revision(),
                UUID.randomUUID(),
                source,
                Map.of(),
                "one",
                now
            )
        ).hasMessage("QUESTION_CHANGED");
        assertThatThrownBy(() ->
            changes.answer(
                state,
                q.id(),
                0,
                state.revision(),
                UUID.randomUUID(),
                source,
                Map.of(),
                "foreign",
                now
            )
        ).hasMessage("UNKNOWN_QUESTION_CHOICE");
        assertThatThrownBy(() ->
            changes.answer(
                state,
                q.id(),
                0,
                state.revision(),
                UUID.randomUUID(),
                source,
                Map.of(),
                "one",
                now.plusSeconds(60)
            )
        ).hasMessage("QUESTION_EXPIRED");
        var answered = changes.answer(
            state,
            q.id(),
            0,
            state.revision(),
            UUID.randomUUID(),
            source,
            Map.of(),
            "one",
            now
        );
        assertThat(answered.slots().get(id).value()).isEqualTo(text("selected"));
        assertThat(answered.slots().get(id).origin()).isEqualTo(Origin.USER_CHOICE);
        assertThat(answered.slots().get(id).sourceMessages()).containsExactly(source);
        assertThat(answered.question()).isNull();
    }

    @Test
    void modelCannotInventUserProvenanceOrOverwriteConfirmedValues() {
        UUID id = UUID.randomUUID();
        var base = Snapshot.empty(UUID.randomUUID());
        var proposed = slot(id, "/name", Set.of());
        assertThatThrownBy(() ->
            changes.propose(
                base,
                0,
                UUID.randomUUID(),
                List.of(proposed),
                Set.of(UUID.randomUUID())
            )
        ).hasMessage("INVALID_SLOT_PROVENANCE");
        var state = initial(List.of(proposed));
        var confirmed = changes.amend(
            state,
            state.revision(),
            UUID.randomUUID(),
            source,
            Map.of(id, text("User"))
        );
        assertThatThrownBy(() ->
            changes.propose(
                confirmed,
                confirmed.revision(),
                UUID.randomUUID(),
                List.of(proposed),
                Set.of(source)
            )
        ).hasMessage("PROPOSAL_CANNOT_OVERWRITE_CONFIRMED_SLOT");
        assertThatThrownBy(() ->
            changes.question(confirmed, question(confirmed, id, List.of()), now)
        ).hasMessage("KNOWN_SLOT_QUESTION");
    }

    @Test
    void wrongSlotTypeScopeAndCyclicDependenciesAreRejectedBeforeAnyChange() {
        UUID id = UUID.randomUUID(),
            other = UUID.randomUUID();
        assertThatThrownBy(() ->
            initial(List.of(slot(id, "/a", Set.of(other)), slot(other, "/b", Set.of(id))))
        ).hasMessage("REQUIREMENTS_DEPENDENCY_CYCLE");
        assertThatThrownBy(() -> initial(List.of(slot(id, "/a", Set.of(other))))).hasMessage(
            "INVALID_SLOT_DEPENDENCY"
        );
        var base = initial(List.of(slot(id, "/a", Set.of()), slot(other, "/b", Set.of())));
        var q = question(base, id, List.of());
        var state = changes.question(base, q, now);
        assertThatThrownBy(() ->
            changes.answer(
                state,
                q.id(),
                0,
                state.revision(),
                UUID.randomUUID(),
                source,
                Map.of(other, text("wrong")),
                null,
                now
            )
        ).hasMessage("ANSWER_SLOT_SCOPE_CHANGED");
        assertThatThrownBy(() ->
            changes.answer(
                state,
                q.id(),
                0,
                state.revision(),
                UUID.randomUUID(),
                source,
                Map.of(id, mapper.createObjectNode().put("type", "INT64").put("value", "7")),
                null,
                now
            )
        ).hasMessage("SLOT_TYPE_MISMATCH");
        assertThat(base.slots().get(id).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void mutableCallerJsonCannotChangeAcceptedState() {
        UUID id = UUID.randomUUID();
        var state = initial(List.of(slot(id, "/name", Set.of())));
        var literal = mapper.createObjectNode().put("type", "TEXT").put("value", "original");
        var next = changes.amend(
            state,
            state.revision(),
            UUID.randomUUID(),
            source,
            Map.of(id, literal)
        );
        literal.put("value", "mutated");
        ((com.fasterxml.jackson.databind.node.ObjectNode) next.slots().get(id).value()).put(
            "value",
            "also-mutated"
        );
        assertThat(next.slots().get(id).value()).isEqualTo(text("original"));
        assertThat(
            validator.projection("plannerResponse").path("$defs").has("collab.WorkerResponse")
        ).isFalse();
    }
}
