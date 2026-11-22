package com.voltcanary.aiagent.adapter.langgraph4j;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.AgentState;

/** Core only: no stock agent executor, provider client, tool loop, scheduler or retry policy. */
public final class LangGraphAgentRunner implements AgentGraphRunner {

    public static final String ENGINE = "langgraph4j-1.9.9";
    public static final String VERSION = "voltcanary-agent-graph-v1";
    private static final int MAX_TRANSITIONS = 32;

    @Override
    public String engine() {
        return ENGINE;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public void runTurn(Graph definition, String threadId) {
        validateTurnGraph(definition, 1, new AtomicInteger(), new AtomicInteger());
        try {
            // Compiled native subgraphs intentionally have no checkpoint saver. A raw library continuation
            // cannot authorize a specialist exchange; its durable task claim and A2 reservation do that.
            var graph = turnGraph(definition, new AtomicInteger());
            var compiled = graph.compile(
                CompileConfig.builder().recursionLimit(MAX_TRANSITIONS).releaseThread(false).build()
            );
            compiled
                .invoke(
                    GraphInput.args(Map.of()),
                    RunnableConfig.builder()
                        .threadId(threadId)
                        .emitterExecutor(Runnable::run)
                        .build()
                )
                .orElseThrow(() -> new IllegalStateException("GRAPH_DID_NOT_COMPLETE"));
        } catch (RuntimeException failure) {
            throw unwrap(failure);
        } catch (Exception failure) {
            throw new IllegalStateException("GRAPH_EXECUTION_FAILED", failure);
        }
    }

    private StateGraph<AgentState> turnGraph(
        Graph definition,
        AtomicInteger transitions
    ) throws org.bsc.langgraph4j.GraphStateException {
        var graph = new StateGraph<>(AgentState::new);
        for (var node : definition.nodes()) {
            graph.addNode(
                node.id(),
                node_async(state -> {
                    if (transitions.incrementAndGet() > 128) throw new IllegalStateException(
                        "COLLABORATION_GRAPH_TRANSITION_LIMIT"
                    );
                    return Map.of("route", node.action().get());
                })
            );
            graph.addConditionalEdges(
                node.id(),
                edge_async(state -> state.<String>value("route").orElseThrow()),
                node
                    .targets()
                    .stream()
                    .collect(Collectors.toMap(Function.identity(), Function.identity()))
            );
        }
        for (var child : definition.subgraphs()) {
            graph.addNode(
                child.id(),
                turnGraph(child.graph(), transitions).compile(
                    CompileConfig.builder()
                        .recursionLimit(MAX_TRANSITIONS)
                        .releaseThread(false)
                        .build()
                )
            );
            graph.addEdge(child.id(), child.next());
        }
        graph.addEdge(StateGraph.START, definition.entry());
        return graph;
    }

    private void validateTurnGraph(
        Graph graph,
        int depth,
        AtomicInteger subgraphs,
        AtomicInteger nodes
    ) {
        if (depth > 3 || graph.subgraphs().size() > 3) throw new IllegalArgumentException(
            "COLLABORATION_GRAPH_DEPTH_OR_FANOUT_LIMIT"
        );
        var names = new HashSet<String>();
        for (var node : graph.nodes()) {
            if (
                node.action() == null || !names.add(node.id()) || !validNodeName(node.id())
            ) throw new IllegalArgumentException("INVALID_COLLABORATION_GRAPH_NODE");
            if (nodes.incrementAndGet() > 128) throw new IllegalArgumentException(
                "COLLABORATION_GRAPH_NODE_LIMIT"
            );
        }
        for (var child : graph.subgraphs()) {
            if (
                !names.add(child.id()) || !validNodeName(child.id())
            ) throw new IllegalArgumentException("INVALID_COLLABORATION_GRAPH_NODE");
            if (subgraphs.incrementAndGet() > 12) throw new IllegalArgumentException(
                "COLLABORATION_SUBGRAPH_LIMIT"
            );
            validateTurnGraph(child.graph(), depth + 1, subgraphs, nodes);
        }
        if (!names.contains(graph.entry())) throw new IllegalArgumentException(
            "INVALID_COLLABORATION_GRAPH_ENTRY"
        );
        for (var node : graph.nodes())
            if (
                node.targets().isEmpty() ||
                node
                    .targets()
                    .stream()
                    .anyMatch(target -> !target.equals(END) && !names.contains(target))
            ) {
                throw new IllegalArgumentException("INVALID_COLLABORATION_GRAPH_TARGET");
            }
        for (var child : graph.subgraphs())
            if (!END.equals(child.next()) && !names.contains(child.next())) {
                throw new IllegalArgumentException("INVALID_COLLABORATION_GRAPH_TARGET");
            }
    }

    private boolean validNodeName(String name) {
        return name != null && name.matches("[a-zA-Z][a-zA-Z0-9_-]{0,79}");
    }

    @Override
    public void run(String entry, List<Node> nodes, String threadId, Persistence persistence) {
        try {
            var graph = new StateGraph<>(AgentState::new);
            for (var node : nodes) {
                graph.addNode(node.id(), node_async(state -> Map.of("route", node.action().get())));
                graph.addConditionalEdges(
                    node.id(),
                    edge_async(state -> state.<String>value("route").orElseThrow()),
                    node
                        .targets()
                        .stream()
                        .collect(Collectors.toMap(Function.identity(), Function.identity()))
                );
            }
            graph.addEdge(StateGraph.START, entry);
            var saver = new PlanSaver(threadId, persistence);
            var compiled = graph.compile(
                CompileConfig.builder()
                    .checkpointSaver(saver)
                    .recursionLimit(MAX_TRANSITIONS)
                    .releaseThread(false)
                    .build()
            );
            // Each explicit user request starts from freshly authorized canonical state.
            // Never resume an abandoned in-flight model dispatch from a raw checkpoint.
            compiled
                .invoke(
                    GraphInput.args(Map.of()),
                    RunnableConfig.builder()
                        .threadId(threadId)
                        .emitterExecutor(Runnable::run)
                        .build()
                )
                .orElseThrow(() -> new IllegalStateException("GRAPH_DID_NOT_COMPLETE"));
        } catch (RuntimeException failure) {
            throw unwrap(failure);
        } catch (Exception failure) {
            throw new IllegalStateException("GRAPH_EXECUTION_FAILED", failure);
        }
    }

    private RuntimeException unwrap(RuntimeException failure) {
        Throwable current = failure;
        while (
            (current instanceof java.util.concurrent.CompletionException ||
                current instanceof java.util.concurrent.ExecutionException ||
                current instanceof org.bsc.langgraph4j.GraphRunnerException) &&
            current.getCause() != null
        ) {
            current = current.getCause();
        }
        return current instanceof RuntimeException runtime
            ? runtime
            : new IllegalStateException("GRAPH_EXECUTION_FAILED", current);
    }

    private static final class PlanSaver implements BaseCheckpointSaver {

        private final String thread;
        private final Persistence persistence;

        private PlanSaver(String thread, Persistence persistence) {
            this.thread = thread;
            this.persistence = persistence;
        }

        private void check(RunnableConfig config) {
            if (
                !config.threadId().orElseThrow().equals(thread) || config.checkPointId().isPresent()
            ) {
                throw new IllegalArgumentException("GRAPH_CHECKPOINT_SCOPE_CHANGED");
            }
        }

        @Override
        public Optional<org.bsc.langgraph4j.checkpoint.Checkpoint> get(RunnableConfig config) {
            check(config);
            return persistence
                .load()
                .map(saved ->
                    new org.bsc.langgraph4j.checkpoint.Checkpoint(
                        saved.id(),
                        saved.values(),
                        saved.node(),
                        saved.next()
                    )
                );
        }

        @Override
        public Collection<org.bsc.langgraph4j.checkpoint.Checkpoint> list(RunnableConfig config) {
            return get(config).map(List::of).orElseGet(List::of);
        }

        @Override
        public RunnableConfig put(
            RunnableConfig config,
            org.bsc.langgraph4j.checkpoint.Checkpoint checkpoint
        ) {
            check(config);
            persistence.save(
                new Checkpoint(
                    checkpoint.id(),
                    checkpoint.nodeId(),
                    checkpoint.nextNodeId(),
                    checkpoint.state()
                )
            );
            return config;
        }

        @Override
        public Tag release(RunnableConfig config) {
            throw new UnsupportedOperationException("GRAPH_HISTORY_RELEASE_DISABLED");
        }

        @Override
        public Tag release(RunnableConfig config, String message) {
            return release(config);
        }

        @Override
        public Tag releaseOnError(RunnableConfig config, Throwable failure) {
            return release(config);
        }

        @Override
        public <S extends AgentState> CompletableFuture<
            InterruptionMetadata<S>
        > registerInterruption(RunnableConfig config, InterruptionMetadata<S> metadata) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("USE_PERSISTED_PLAN_QUESTION_OR_CONFIRMATION")
            );
        }

        @Override
        public Optional<Tag> tag(RunnableConfig config, Integer version) {
            return Optional.empty();
        }

        @Override
        public void putSubGraphSaver(
            RunnableConfig parent,
            RunnableConfig child,
            BaseCheckpointSaver saver
        ) {
            throw new UnsupportedOperationException("NESTED_CHECKPOINT_NAMESPACE_NOT_QUALIFIED");
        }

        @Override
        public Collection<SubGraphSaver> listSubGraphSaver(RunnableConfig config) {
            return List.of();
        }
    }
}
