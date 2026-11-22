package com.voltcanary.aiagent.orchestration.collaboration;

import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.conflict;
import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Event;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Origin;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Slot;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Snapshot;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Pure versioned changes. The application supplies verified message/evidence identity and registered schemas. */
@RequiredArgsConstructor
public final class RequirementChanges {

    public interface Types {
        void validate(String schemaId, JsonNode literal);
    }

    private final Types types;

    public Snapshot propose(
        Snapshot before,
        long expected,
        UUID request,
        List<Slot> proposals,
        Set<UUID> authorizedMessages
    ) {
        revision(before, expected);
        if (request == null || proposals.size() > 128) throw invalid("INVALID_REQUIREMENTS_PATCH");
        var slots = new LinkedHashMap<>(before.slots());
        var changed = new HashSet<UUID>();
        for (var proposal : proposals) {
            if (
                !Set.of(Status.UNKNOWN, Status.PROPOSED).contains(proposal.status()) ||
                proposal.origin() != Origin.MODEL_PROPOSED ||
                !authorizedMessages.containsAll(proposal.sourceMessages()) ||
                proposal.sourceMessages().isEmpty() ||
                !proposal.evidence().isEmpty() ||
                !changed.add(proposal.id())
            ) throw invalid("INVALID_SLOT_PROVENANCE");
            var old = slots.get(proposal.id());
            if (
                old != null &&
                (!old.path().equals(proposal.path()) ||
                    !old.schemaId().equals(proposal.schemaId()) ||
                    old.required() != proposal.required())
            ) throw conflict("SLOT_CONTRACT_CHANGED");
            if (old != null && Set.of(Status.CONFIRMED, Status.DERIVED).contains(old.status())) {
                throw conflict("PROPOSAL_CANNOT_OVERWRITE_CONFIRMED_SLOT");
            }
            if (proposal.value() != null) types.validate(proposal.schemaId(), proposal.value());
            slots.put(
                proposal.id(),
                new Slot(
                    proposal.id(),
                    proposal.path(),
                    proposal.schemaId(),
                    proposal.required(),
                    proposal.status(),
                    proposal.value(),
                    proposal.sourceMessages(),
                    Set.of(),
                    proposal.dependsOn(),
                    old == null ? 0 : next(old.revision()),
                    Origin.MODEL_PROPOSED
                )
            );
        }
        if (changed.isEmpty()) throw invalid("EMPTY_REQUIREMENTS_PATCH");
        validateGraph(slots);
        return updated(before, slots, changed, request, null, Origin.MODEL_PROPOSED);
    }

    public Snapshot answer(
        Snapshot before,
        UUID questionId,
        long expectedQuestion,
        long expectedRequirements,
        UUID request,
        UUID verifiedUserMessage,
        Map<UUID, JsonNode> values,
        String choiceId,
        Instant now
    ) {
        return answer(
            before,
            questionId,
            expectedQuestion,
            expectedRequirements,
            request,
            verifiedUserMessage,
            values,
            choiceId,
            null,
            now
        );
    }

    public Snapshot answerText(
        Snapshot before,
        UUID questionId,
        long expectedQuestion,
        long expectedRequirements,
        UUID request,
        UUID verifiedUserMessage,
        String text,
        Instant now
    ) {
        if (text == null || text.isBlank() || text.length() > 4000) throw invalid(
            "INVALID_REQUIREMENT_TEXT"
        );
        return answer(
            before,
            questionId,
            expectedQuestion,
            expectedRequirements,
            request,
            verifiedUserMessage,
            Map.of(),
            null,
            text,
            now
        );
    }

    private Snapshot answer(
        Snapshot before,
        UUID questionId,
        long expectedQuestion,
        long expectedRequirements,
        UUID request,
        UUID verifiedUserMessage,
        Map<UUID, JsonNode> values,
        String choiceId,
        String text,
        Instant now
    ) {
        revision(before, expectedRequirements);
        var question = before.question();
        if (
            question == null ||
            !question.id().equals(questionId) ||
            question.revision() != expectedQuestion ||
            question.requirementsRevision() != before.revision()
        ) throw conflict("QUESTION_CHANGED");
        if (!question.expiresAt().isAfter(now)) throw new Requirements.Rejected(
            410,
            "QUESTION_EXPIRED"
        );
        if (verifiedUserMessage == null || request == null) throw invalid("USER_SOURCE_REQUIRED");
        Origin origin = Origin.USER_VALUES;
        if (choiceId != null) {
            if (!values.isEmpty()) throw invalid("MIXED_REQUIREMENT_ANSWER");
            values = question
                .choices()
                .stream()
                .filter(choice -> choice.id().equals(choiceId))
                .findFirst()
                .orElseThrow(() -> invalid("UNKNOWN_QUESTION_CHOICE"))
                .bindings();
            origin = Origin.USER_CHOICE;
        }
        if (text != null) {
            if (question.slots().size() != 1 || !question.choices().isEmpty()) throw invalid(
                "AMBIGUOUS_REQUIREMENT_TEXT"
            );
            values = Map.of(
                question.slots().getFirst(),
                JsonNodeFactory.instance.objectNode().put("type", "TEXT").put("value", text)
            );
            origin = Origin.USER_TEXT;
        }
        if (values.isEmpty() || !question.slots().containsAll(values.keySet())) throw invalid(
            "ANSWER_SLOT_SCOPE_CHANGED"
        );
        var slots = new LinkedHashMap<>(before.slots());
        for (var entry : values.entrySet()) {
            var old = slot(before, entry.getKey());
            types.validate(old.schemaId(), entry.getValue());
            slots.put(
                old.id(),
                new Slot(
                    old.id(),
                    old.path(),
                    old.schemaId(),
                    old.required(),
                    Status.CONFIRMED,
                    entry.getValue(),
                    Set.of(verifiedUserMessage),
                    Set.of(),
                    old.dependsOn(),
                    next(old.revision()),
                    origin
                )
            );
        }
        return updated(before, slots, values.keySet(), request, verifiedUserMessage, origin);
    }

    public Snapshot question(Snapshot before, Requirements.Question question, Instant now) {
        if (
            !question.expiresAt().isAfter(now) ||
            question.requirementsRevision() != before.revision()
        ) throw conflict("QUESTION_REQUIREMENTS_CHANGED");
        if (
            question.revision() !=
            (before.question() == null ? 0 : next(before.question().revision()))
        ) {
            throw conflict("QUESTION_REVISION_CHANGED");
        }
        for (UUID id : question.slots()) {
            if (
                Set.of(Status.CONFIRMED, Status.DERIVED).contains(slot(before, id).status())
            ) throw invalid("KNOWN_SLOT_QUESTION");
        }
        for (var choice : question.choices())
            choice
                .bindings()
                .forEach((id, value) -> types.validate(slot(before, id).schemaId(), value));
        return new Snapshot(
            before.version(),
            before.planId(),
            before.revision(),
            before.slots(),
            question,
            before.events()
        );
    }

    /** Explicit typed user edit, separate from model proposals and short answers to open questions. */
    public Snapshot amend(
        Snapshot before,
        long expected,
        UUID request,
        UUID verifiedUserMessage,
        Map<UUID, JsonNode> values
    ) {
        revision(before, expected);
        if (
            request == null || verifiedUserMessage == null || values.isEmpty() || values.size() > 16
        ) {
            throw invalid("INVALID_USER_REQUIREMENTS_PATCH");
        }
        var slots = new LinkedHashMap<>(before.slots());
        for (var entry : values.entrySet()) {
            var old = slot(before, entry.getKey());
            types.validate(old.schemaId(), entry.getValue());
            slots.put(
                old.id(),
                new Slot(
                    old.id(),
                    old.path(),
                    old.schemaId(),
                    old.required(),
                    Status.CONFIRMED,
                    entry.getValue(),
                    Set.of(verifiedUserMessage),
                    Set.of(),
                    old.dependsOn(),
                    next(old.revision()),
                    Origin.USER_VALUES
                )
            );
        }
        return updated(
            before,
            slots,
            values.keySet(),
            request,
            verifiedUserMessage,
            Origin.USER_VALUES
        );
    }

    public Snapshot confirmKnowledgeScope(
        Snapshot before,
        UUID request,
        UUID source,
        JsonNode value
    ) {
        if (before.question() != null) throw conflict("KNOWLEDGE_SCOPE_REQUIRES_RESOLVED_QUESTION");
        if (request == null || source == null || !value.isArray() || value.size() > 32) {
            throw invalid("INVALID_KNOWLEDGE_CORPUS_SCOPE");
        }
        var unique = new HashSet<UUID>();
        for (var item : value) {
            try {
                var id = UUID.fromString(item.asText());
                if (
                    !id.toString().equals(item.textValue()) || !unique.add(id)
                ) throw new IllegalArgumentException();
            } catch (IllegalArgumentException malformed) {
                throw invalid("INVALID_KNOWLEDGE_CORPUS_SCOPE");
            }
        }
        var slots = new LinkedHashMap<>(before.slots());
        var previous = slots
            .values()
            .stream()
            .filter(slot -> "/knowledge/corpusRefs".equals(slot.path()))
            .findFirst()
            .orElse(null);
        UUID id =
            previous == null
                ? UUID.nameUUIDFromBytes(
                      (before.planId() + ":knowledge-corpora").getBytes(
                          java.nio.charset.StandardCharsets.UTF_8
                      )
                  )
                : previous.id();
        slots.put(
            id,
            new Slot(
                id,
                "/knowledge/corpusRefs",
                "knowledge-corpus-refs-v1",
                false,
                Status.CONFIRMED,
                value,
                Set.of(source),
                Set.of(),
                Set.of(),
                previous == null ? 0 : next(previous.revision()),
                Origin.OWNER_EVIDENCE
            )
        );
        return updated(before, slots, Set.of(id), request, source, Origin.OWNER_EVIDENCE);
    }

    public boolean complete(Snapshot state) {
        return state
            .slots()
            .values()
            .stream()
            .filter(Slot::required)
            .allMatch(slot -> Set.of(Status.CONFIRMED, Status.DERIVED).contains(slot.status()));
    }

    public Snapshot markConflict(
        Snapshot before,
        long expected,
        UUID request,
        Set<UUID> ids,
        Set<UUID> verifiedEvidence
    ) {
        revision(before, expected);
        if (request == null || ids.isEmpty()) throw invalid("EMPTY_REQUIREMENTS_CONFLICT");
        var slots = new LinkedHashMap<>(before.slots());
        for (UUID id : ids) {
            var old = slot(before, id);
            if (Set.of(Status.CONFIRMED, Status.DERIVED).contains(old.status())) throw conflict(
                "CONFIRMED_SLOT_REQUIRES_EXPLICIT_POLICY_REVIEW"
            );
            slots.put(
                id,
                new Slot(
                    id,
                    old.path(),
                    old.schemaId(),
                    old.required(),
                    Status.CONFLICT,
                    null,
                    old.sourceMessages(),
                    verifiedEvidence,
                    old.dependsOn(),
                    next(old.revision()),
                    Origin.MODEL_PROPOSED
                )
            );
        }
        return updated(before, slots, ids, request, null, Origin.MODEL_PROPOSED);
    }

    private Snapshot updated(
        Snapshot before,
        Map<UUID, Slot> slots,
        Set<UUID> changed,
        UUID request,
        UUID source,
        Origin origin
    ) {
        var invalidated = new HashSet<UUID>();
        boolean expanded;
        do {
            expanded = false;
            for (var slot : List.copyOf(slots.values())) {
                if (changed.contains(slot.id()) || invalidated.contains(slot.id())) continue;
                if (
                    slot
                        .dependsOn()
                        .stream()
                        .anyMatch(id -> changed.contains(id) || invalidated.contains(id))
                ) {
                    slots.put(
                        slot.id(),
                        new Slot(
                            slot.id(),
                            slot.path(),
                            slot.schemaId(),
                            slot.required(),
                            Status.STALE,
                            null,
                            slot.sourceMessages(),
                            slot.evidence(),
                            slot.dependsOn(),
                            next(slot.revision()),
                            slot.origin()
                        )
                    );
                    invalidated.add(slot.id());
                    expanded = true;
                }
            }
        } while (expanded);
        long revision = next(before.revision());
        var events = new ArrayList<>(before.events());
        events.add(new Event(revision, request, changed, invalidated, source, origin));
        return new Snapshot(before.version(), before.planId(), revision, slots, null, events);
    }

    private void validateGraph(Map<UUID, Slot> slots) {
        var paths = new HashSet<String>();
        for (var slot : slots.values()) {
            if (
                !paths.add(slot.path()) || !slots.keySet().containsAll(slot.dependsOn())
            ) throw invalid("INVALID_SLOT_DEPENDENCY");
        }
        var colors = new HashMap<UUID, Integer>();
        for (UUID id : slots.keySet()) visit(id, slots, colors);
    }

    private void visit(UUID id, Map<UUID, Slot> slots, Map<UUID, Integer> colors) {
        if (colors.getOrDefault(id, 0) == 1) throw invalid("REQUIREMENTS_DEPENDENCY_CYCLE");
        if (colors.getOrDefault(id, 0) == 2) return;
        colors.put(id, 1);
        slots
            .get(id)
            .dependsOn()
            .forEach(dependency -> visit(dependency, slots, colors));
        colors.put(id, 2);
    }

    private Slot slot(Snapshot state, UUID id) {
        var value = state.slots().get(id);
        if (value == null) throw invalid("UNKNOWN_REQUIREMENT_SLOT");
        return value;
    }

    private static void revision(Snapshot before, long expected) {
        if (before.revision() != expected) throw conflict("REQUIREMENTS_REVISION_CHANGED");
    }

    private static long next(long revision) {
        if (revision >= Requirements.MAX_REVISION) throw invalid("REQUIREMENTS_REVISION_LIMIT");
        return revision + 1;
    }
}
