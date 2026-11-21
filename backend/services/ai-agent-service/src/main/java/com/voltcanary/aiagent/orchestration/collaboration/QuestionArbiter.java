package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Choice;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Question;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Snapshot;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** One root question for the nearest unresolved slot group; duplicates are not independent evidence. */
@RequiredArgsConstructor
public final class QuestionArbiter {
    public record Decision(Snapshot requirements, Set<UUID> taskIds) {
        public Decision { taskIds = Set.copyOf(taskIds); }
    }
    private final RequirementChanges changes;

    public Decision decide(Snapshot before, List<Task> waiting, Set<UUID> currentEvidence,
            UUID questionId, UUID requestId, Instant now, Instant expiresAt) {
        var groups = new java.util.TreeMap<String, List<Candidate>>();
        for (var task : waiting) {
            if (task.status() != Consultations.Status.WAITING_USER || task.result() == null || task.assignment() == null) continue;
            for (var candidate : task.result().path("questions")) {
                var ids = new ArrayList<UUID>();
                boolean stale = false;
                for (var item : candidate.path("slotIds")) {
                    UUID id = UUID.fromString(item.asText());
                    var slot = before.slots().get(id);
                    var pinned = task.assignment().reads().slots().get(id);
                    if (slot == null || pinned == null || pinned != slot.revision()) stale = true;
                    else if (!Set.of(Status.CONFIRMED, Status.DERIVED).contains(slot.status())) ids.add(id);
                }
                if (stale || ids.isEmpty()) continue;
                var groupSlots = Set.copyOf(ids);
                boolean blocked = ids.stream().map(before.slots()::get).flatMap(slot -> slot.dependsOn().stream())
                        .filter(dependency -> !groupSlots.contains(dependency)).map(before.slots()::get)
                        .anyMatch(dependency -> dependency == null || !Set.of(Status.CONFIRMED, Status.DERIVED).contains(dependency.status()));
                if (blocked) continue;
                var evidence = new LinkedHashSet<UUID>();
                for (var item : candidate.path("evidenceRefs")) evidence.add(UUID.fromString(item.asText()));
                if (!currentEvidence.containsAll(evidence)) throw Requirements.invalid("QUESTION_EVIDENCE_REVOKED");
                ids.sort(java.util.Comparator.comparing(id -> before.slots().get(id).path()));
                String key = ids.stream().map(id -> before.slots().get(id).path()).collect(java.util.stream.Collectors.joining("\n"));
                var choices = new ArrayList<Choice>();
                for (var choice : candidate.path("choices")) {
                    var bindings = new LinkedHashMap<UUID, JsonNode>();
                    choice.path("bindings").fields().forEachRemaining(entry -> bindings.put(UUID.fromString(entry.getKey()), entry.getValue()));
                    if (!Set.copyOf(ids).containsAll(bindings.keySet())) throw Requirements.invalid("QUESTION_CHOICE_OUTSIDE_UNRESOLVED_SLOTS");
                    choices.add(new Choice(choice.path("choiceId").asText(), choice.path("label").asText(), bindings));
                }
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Candidate(task.id(), ids,
                        candidate.path("text").asText(), choices, evidence));
            }
        }
        if (groups.isEmpty()) return new Decision(before, Set.of());
        var selected = groups.firstEntry().getValue();
        var first = selected.getFirst();
        var sources = new LinkedHashSet<UUID>();
        var tasks = new LinkedHashSet<UUID>();
        var texts = new LinkedHashSet<String>();
        boolean contradictory = false;
        var offered = bindingSets(first.choices());
        for (var candidate : selected) {
            sources.addAll(candidate.evidence());
            tasks.add(candidate.task());
            texts.add(candidate.text());
            if (!offered.equals(bindingSets(candidate.choices()))) contradictory = true;
        }
        Snapshot current = before;
        List<Choice> choices = first.choices();
        String text = String.join("\n", texts.stream().limit(3).toList());
        if (contradictory) {
            current = changes.markConflict(before, before.revision(), requestId, Set.copyOf(first.slots()), sources);
            choices = List.of();
            text = "Источники предлагают разные значения. " + text;
        }
        if (text.length() > 6000) throw Requirements.invalid("ROOT_QUESTION_SIZE_LIMIT");
        long revision = current.question() == null ? 0 : current.question().revision() + 1;
        var question = new Question(questionId, revision, current.revision(), first.slots(), text, choices, sources, expiresAt);
        return new Decision(changes.question(current, question, now), tasks);
    }

    private Set<Map<UUID, JsonNode>> bindingSets(List<Choice> choices) {
        return choices.stream().map(Choice::bindings).collect(java.util.stream.Collectors.toSet());
    }
    private record Candidate(UUID task, List<UUID> slots, String text, List<Choice> choices, Set<UUID> evidence) { }
}
