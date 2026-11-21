package com.voltcanary.aiagent.adapter.springai;

import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

record PreparedManifestAdvisor(List<Message> messages, ChatOptions options, ReviewedProposalTool tool) implements CallAdvisor {
    PreparedManifestAdvisor {
        messages = List.copyOf(messages);
    }

    @Override public String getName() { return "PreparedManifestAdvisor"; }
    @Override public int getOrder() { return 0; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!messages.equals(request.prompt().getInstructions())) throw new ModelRouting.Stop("PREPARED_MESSAGES_CHANGED");
        var actual = request.prompt().getOptions();
        if (actual instanceof ToolCallingChatOptions toolOptions) {
            var callbacks = toolOptions.getToolCallbacks() == null ? List.of() : toolOptions.getToolCallbacks();
            if (!Objects.equals(callbacks, tool == null ? List.of() : List.of(tool))) {
                throw new ModelRouting.Stop("PREPARED_TOOLS_CHANGED");
            }
            actual = toolOptions.mutate().toolCallbacks(List.of()).build();
        } else if (tool != null) {
            throw new ModelRouting.Stop("PREPARED_TOOLS_CHANGED");
        }
        var expected = options instanceof ToolCallingChatOptions defaults
                ? defaults.mutate().toolCallbacks(List.of()).build() : options;
        if (!Objects.equals(expected, actual)) throw new ModelRouting.Stop("PREPARED_OPTIONS_CHANGED");
        return chain.nextCall(request);
    }
}
