package com.voltcanary.aiagent.orchestration.collaboration;

import com.voltcanary.aiagent.orchestration.plan.IntentPlans.Token;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Application boundary for private knowledge. Ranking never returns document text. */
public final class PrivateKnowledge {
    private static final int CHANNEL_LIMIT = 40;
    private static final int RANK_OFFSET = 60;
    public record Scope(UUID accountId, UUID principalId, Set<UUID> corpusIds) {
        public Scope {
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(principalId);
            corpusIds = Set.copyOf(corpusIds);
        }
    }

    public record Hit(UUID corpusId, UUID sourceId, String sourceVersion, UUID chunkId) { }

    public record Evidence(Hit hit, String text) { }

    /** Server-held search result. Only the ordered hit metadata is projected to a caller. */
    public static final class Selection {
        private final UUID planId;
        private final Scope scope;
        private final long requirementsRevision;
        private final List<Hit> hits;

        private Selection(UUID planId, Scope scope, long requirementsRevision, List<Hit> hits) {
            this.planId = planId;
            this.scope = scope;
            this.requirementsRevision = requirementsRevision;
            this.hits = List.copyOf(hits);
        }

        public List<Hit> hits() { return hits; }
    }

    public Selection select(CollaborationStore store, Token turn, long requirementsRevision,
            Set<UUID> corpusRefs, java.util.function.Function<Scope, List<Hit>> search) {
        var scope = planScope(store, turn, requirementsRevision, corpusRefs);
        var hits = search.apply(scope);
        planScope(store, turn, requirementsRevision, corpusRefs);
        return new Selection(turn.planId(), scope, requirementsRevision, hits);
    }

    public List<Evidence> resolve(CollaborationStore store, Token turn, Selection selection, List<Hit> requested) {
        if (!selection.planId.equals(turn.planId())
                || !selection.scope.accountId().equals(turn.owner().accountId())
                || !selection.scope.principalId().equals(turn.owner().userId())
                || !selection.hits.containsAll(requested)) {
            throw Requirements.conflict("KNOWLEDGE_SELECTION_MISMATCH");
        }
        return resolve(store, turn, selection.requirementsRevision, selection.scope.corpusIds(), requested);
    }

    /** Bind the requested subset to the current, owner-evidenced plan scope. */
    public static Scope planScope(CollaborationStore store, Token turn,
            long requirementsRevision, Set<UUID> requested) {
        var requirements = store.read(turn.owner(), turn.planId()).requirements();
        if (requirements.revision() != requirementsRevision) {
            throw Requirements.conflict("KNOWLEDGE_REQUIREMENTS_CHANGED");
        }
        var allowed = new java.util.HashSet<UUID>();
        for (var slot : requirements.slots().values()) {
            if (!"/knowledge/corpusRefs".equals(slot.path()) || slot.origin() != Requirements.Origin.OWNER_EVIDENCE
                    || slot.status() != Requirements.Status.CONFIRMED) continue;
            var value = slot.value();
            if (!value.isArray() || value.size() > 32) throw Requirements.invalid("INVALID_KNOWLEDGE_CORPUS_SCOPE");
            for (var item : value) {
                try {
                    allowed.add(UUID.fromString(item.asText()));
                } catch (IllegalArgumentException invalid) {
                    throw Requirements.invalid("INVALID_KNOWLEDGE_CORPUS_SCOPE");
                }
            }
        }
        if (requested.size() > 32 || !allowed.containsAll(requested)) {
            throw Requirements.conflict("KNOWLEDGE_CORPUS_SCOPE_DENIED");
        }
        return new Scope(turn.owner().accountId(), turn.owner().userId(), requested);
    }

    @FunctionalInterface
    public interface CandidateIndex {
        List<Hit> searchIds(Scope scope, String query, int limit);

        default List<Hit> searchVectorIds(Scope scope, UUID generationId, float[] embedding, int limit) {
            throw new UnsupportedOperationException("KNOWLEDGE_VECTOR_UNAVAILABLE");
        }
    }

    @FunctionalInterface
    public interface OwnerAccess {
        Set<Hit> authorizeCurrent(Scope scope, List<Hit> candidates);

        default Set<UUID> authorizeCorpora(Scope scope) {
            return Set.of();
        }
    }

    @FunctionalInterface
    public interface EvidenceResolver {
        List<Evidence> hydrate(Scope scope, List<Hit> authorized);
    }

    private final CandidateIndex index;
    private final OwnerAccess access;
    private final EvidenceResolver resolver;

    public PrivateKnowledge(CandidateIndex index, OwnerAccess access, EvidenceResolver resolver) {
        this.index = Objects.requireNonNull(index);
        this.access = Objects.requireNonNull(access);
        this.resolver = Objects.requireNonNull(resolver);
    }

    public Set<UUID> authorizeCorpora(Scope requested) {
        if (requested.corpusIds().size() > 32) throw Requirements.invalid("INVALID_KNOWLEDGE_CORPUS_SCOPE");
        if (requested.corpusIds().isEmpty()) return Set.of();
        var authorized = Set.copyOf(access.authorizeCorpora(requested));
        if (!requested.corpusIds().containsAll(authorized)) {
            throw Requirements.conflict("KNOWLEDGE_CORPUS_SCOPE_DENIED");
        }
        return authorized;
    }

    public List<Hit> search(Scope scope, String query, int limit) {
        if (query.isBlank() || limit < 1 || limit > 80) {
            throw Requirements.invalid("INVALID_KNOWLEDGE_SEARCH");
        }
        if (scope.corpusIds().isEmpty()) return List.of();
        return authorized(scope, index.searchIds(scope, query, limit), limit);
    }

    /** The caller supplies an embedding from its reserved, generation-pinned exchange. */
    public List<Hit> searchHybrid(Scope scope, String query, UUID generationId, float[] embedding, int limit) {
        if (query.isBlank() || limit < 1 || limit > 80) {
            throw Requirements.invalid("INVALID_KNOWLEDGE_SEARCH");
        }
        if (scope.corpusIds().isEmpty()) return List.of();
        var lexical = channel(index.searchIds(scope, query, CHANNEL_LIMIT));
        var vector = channel(index.searchVectorIds(scope, generationId, embedding, CHANNEL_LIMIT));
        var allowed = Set.copyOf(authorized(scope, java.util.stream.Stream.concat(lexical.stream(), vector.stream())
                .toList(), 2 * CHANNEL_LIMIT));
        var scores = new LinkedHashMap<Hit, Double>();
        addRanks(scores, lexical, allowed);
        addRanks(scores, vector, allowed);
        return scores.keySet().stream()
                .sorted(Comparator.comparing((Hit hit) -> !exactIdentity(hit, query))
                        .thenComparing(scores::get, Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    private static boolean exactIdentity(Hit hit, String query) {
        return hit.sourceId().toString().equals(query) || hit.chunkId().toString().equals(query);
    }

    private static List<Hit> channel(List<Hit> hits) {
        if (hits.size() > CHANNEL_LIMIT) throw Requirements.invalid("KNOWLEDGE_CANDIDATE_LIMIT");
        return hits.stream().distinct().toList();
    }

    private static void addRanks(Map<Hit, Double> scores, List<Hit> hits, Set<Hit> allowed) {
        int rank = 0;
        for (var hit : hits) {
            if (allowed.contains(hit)) scores.merge(hit, 1.0 / (RANK_OFFSET + ++rank), Double::sum);
        }
    }

    public List<Evidence> resolve(CollaborationStore store, Token turn, long requirementsRevision,
            Set<UUID> corpusRefs, List<Hit> candidates) {
        var scope = planScope(store, turn, requirementsRevision, corpusRefs);
        var evidence = resolve(scope, candidates);
        planScope(store, turn, requirementsRevision, corpusRefs);
        return evidence;
    }

    public void verifyCurrent(CollaborationStore store, Token turn, long revision, List<Hit> hits) {
        verifyCurrent(store, turn, revision, hits, 8);
    }

    public void verifyCandidates(CollaborationStore store, Token turn, long revision, List<Hit> hits) {
        verifyCurrent(store, turn, revision, hits, CHANNEL_LIMIT);
    }

    private void verifyCurrent(CollaborationStore store, Token turn, long revision, List<Hit> hits, int limit) {
        var corpora = hits.stream().map(Hit::corpusId).collect(java.util.stream.Collectors.toSet());
        var scope = planScope(store, turn, revision, corpora);
        if (!authorized(scope, hits, limit).containsAll(hits)) {
            throw Requirements.conflict("KNOWLEDGE_EVIDENCE_ACCESS_CHANGED");
        }
    }

    public List<Evidence> resolve(Scope scope, List<Hit> candidates) {
        var authorized = authorized(scope, candidates, 8);
        if (authorized.isEmpty()) return List.of();
        var permitted = Set.copyOf(authorized);
        Map<Hit, Evidence> hydrated = new HashMap<>();
        for (var evidence : resolver.hydrate(scope, authorized)) {
            if (!permitted.contains(evidence.hit()) || hydrated.putIfAbsent(evidence.hit(), evidence) != null) {
                throw Requirements.conflict("KNOWLEDGE_EVIDENCE_MISMATCH");
            }
        }
        var hydratedHits = authorized.stream().filter(hydrated::containsKey).toList();
        return authorized(scope, hydratedHits, 8).stream().map(hydrated::get).toList();
    }

    private List<Hit> authorized(Scope scope, List<Hit> candidates, int limit) {
        if (candidates.size() > limit) throw Requirements.invalid("KNOWLEDGE_CANDIDATE_LIMIT");
        var scoped = candidates.stream().filter(hit -> scope.corpusIds().contains(hit.corpusId())).distinct().toList();
        if (scoped.isEmpty()) return List.of();
        var allowed = access.authorizeCurrent(scope, scoped);
        return scoped.stream().filter(allowed::contains).toList();
    }
}
