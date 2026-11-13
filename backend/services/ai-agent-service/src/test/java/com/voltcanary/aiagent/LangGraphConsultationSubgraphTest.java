package com.voltcanary.aiagent;

import com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner;
import com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner.Graph;
import com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner.Node;
import com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner.Subgraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner.END;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangGraphConsultationSubgraphTest {
    private final LangGraphAgentRunner runner = new LangGraphAgentRunner();

    @Test
    void libraryRunsRootChildAndGrandchildOnCallingThread() {
        var order = new ArrayList<String>();
        var thread = Thread.currentThread();
        var grandchild = new Graph("metrics", List.of(new Node("metrics", () -> {
            assertThat(Thread.currentThread()).isSameAs(thread);
            order.add("metrics");
            return END;
        }, Set.of(END))), List.of());
        var child = new Graph("schema", List.of(new Node("schema", () -> {
            order.add("schema");
            return "metrics_consult";
        }, Set.of("metrics_consult"))), List.of(new Subgraph("metrics_consult", grandchild, END)));
        var root = new Graph("authorize", List.of(new Node("authorize", () -> {
            order.add("authorize");
            return "schema_consult";
        }, Set.of("schema_consult")), new Node("accept", () -> {
            order.add("accept");
            return END;
        }, Set.of(END))), List.of(new Subgraph("schema_consult", child, "accept")));
        runner.runTurn(root, "consultation-fixture");
        assertThat(order).containsExactly("authorize", "schema", "metrics", "accept");
    }

    @Test
    void invalidDepthOrTargetsAreRejectedBeforeAnyActionRuns() {
        var calls = new AtomicInteger();
        var leaf = new Graph("leaf", List.of(new Node("leaf", () -> {
            calls.incrementAndGet();
            return END;
        }, Set.of(END))), List.of());
        var depth2 = new Graph("child", List.of(), List.of(new Subgraph("child", leaf, END)));
        var depth3 = new Graph("child", List.of(), List.of(new Subgraph("child", depth2, END)));
        var depth4 = new Graph("child", List.of(), List.of(new Subgraph("child", depth3, END)));
        assertThatThrownBy(() -> runner.runTurn(depth4, "fixture")).hasMessage("COLLABORATION_GRAPH_DEPTH_OR_FANOUT_LIMIT");
        var invalid = new Graph("leaf", leaf.nodes(), List.of(new Subgraph("child", leaf, "missing")));
        assertThatThrownBy(() -> runner.runTurn(invalid, "fixture")).hasMessage("INVALID_COLLABORATION_GRAPH_TARGET");
        assertThat(calls).hasValue(0);
    }

    @Test
    void workerFailureEscapesWithoutRetryOrAcceptNode() {
        var calls = new AtomicInteger();
        var failed = new IllegalStateException("CONTROLLED_WORKER_FAILURE");
        var child = new Graph("exchange", List.of(new Node("exchange", () -> {
            calls.incrementAndGet();
            throw failed;
        }, Set.of(END))), List.of());
        var root = new Graph("worker", List.of(new Node("accept", () -> {
            throw new AssertionError("No accept after failed exchange");
        }, Set.of(END))), List.of(new Subgraph("worker", child, "accept")));
        assertThatThrownBy(() -> runner.runTurn(root, "fixture")).isSameAs(failed);
        assertThat(calls).hasValue(1);
    }
}
