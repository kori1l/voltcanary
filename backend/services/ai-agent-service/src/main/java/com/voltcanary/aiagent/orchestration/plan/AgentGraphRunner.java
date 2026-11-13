package com.voltcanary.aiagent.orchestration.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Library-neutral execution port. Nodes return validated protocol routes, never language matches. */
public interface AgentGraphRunner {
    String END = "__END__";

    record Node(String id, Supplier<String> action, Set<String> targets) {
        public Node {
            targets = Set.copyOf(targets);
        }
    }

    record Checkpoint(String id, String node, String next, Map<String, Object> values) {
        public Checkpoint {
            values = Map.copyOf(values);
        }
    }

    /** In-turn subgraphs have no independent resume/dispatch authority. Durable task claims live in the plan store. */
    record Subgraph(String id, Graph graph, String next) { }
    record Graph(String entry, List<Node> nodes, List<Subgraph> subgraphs) {
        public Graph {
            nodes = List.copyOf(nodes);
            subgraphs = List.copyOf(subgraphs);
        }
    }

    interface Persistence {
        Optional<Checkpoint> load();
        void save(Checkpoint checkpoint);
    }

    /** In-turn graphs cannot resume independently; their model attempts remain in the shared ledger. */
    static Persistence inTurn() {
        return new Persistence() {
            public Optional<Checkpoint> load() { return Optional.empty(); }
            public void save(Checkpoint checkpoint) { }
        };
    }

    String engine();
    String version();
    void run(String entry, List<Node> nodes, String threadId, Persistence persistence);

    default void runTurn(Graph graph, String threadId) {
        if (!graph.subgraphs().isEmpty()) throw new UnsupportedOperationException("SUBGRAPHS_UNAVAILABLE");
        run(graph.entry(), graph.nodes(), threadId, inTurn());
    }
}
