package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

/** Library API qualification only. The application uses its own atomic plan-backed saver. */
class LangGraphCompatibilityTest {

    @Test
    void releasedCoreSupportsBoundedSubgraphsAndCheckpointedHumanPause() throws Exception {
        var child = new StateGraph<>(AgentState::new)
            .addNode("context", node_async(state -> Map.of("contextLoaded", true)))
            .addEdge(StateGraph.START, "context")
            .addEdge("context", StateGraph.END);
        var saver = new MemorySaver();
        var graph = new StateGraph<>(AgentState::new)
            .addNode("specialist", child)
            .addNode(
                "approval",
                node_async(state -> {
                    assertThat(state.<Boolean>value("approved")).contains(true);
                    return Map.of("result", "accepted");
                })
            )
            .addEdge(StateGraph.START, "specialist")
            .addEdge("specialist", "approval")
            .addEdge("approval", StateGraph.END)
            .compile(
                CompileConfig.builder()
                    .checkpointSaver(saver)
                    .interruptBefore("approval")
                    .recursionLimit(12)
                    .releaseThread(false)
                    .build()
            );
        var config = RunnableConfig.builder()
            .threadId("isolated-compatibility")
            .emitterExecutor(Runnable::run)
            .build();
        graph.invokeSync(GraphInput.noArgs(), config);
        var paused = saver.get(config).orElseThrow();
        assertThat(paused.nextNodeId()).isEqualTo("approval");
        assertThat(paused.state()).containsEntry("contextLoaded", true).doesNotContainKey("result");
        var result = graph.invokeSync(GraphInput.resume(Map.of("approved", true)), config);
        assertThat(result.asStateData())
            .containsEntry("result", "accepted")
            .containsEntry("contextLoaded", true);
    }
}
