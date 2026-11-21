package com.voltcanary.aiagent.adapter.springai;

import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreparedManifestAdvisorTest {
    @Test
    void springChainRejectsChangedOptionsAndToolBeforeModelInvocation() {
        var model = mock(ChatModel.class);
        var options = OpenAiChatOptions.builder().model("reserved").maxTokens(200).build();
        when(model.getOptions()).thenReturn(options);
        var message = new UserMessage("Reserved message");
        var advisor = new PreparedManifestAdvisor(List.of(message), options, null);
        var client = ChatClient.create(model);

        var changedOptions = assertThrows(ModelRouting.Stop.class, () -> client.prompt().messages(List.of(message))
                .options(OpenAiChatOptions.builder().model("different"))
                .advisors(advisor).call().chatResponse());
        assertEquals("PREPARED_OPTIONS_CHANGED", changedOptions.getMessage());

        var changedTools = assertThrows(ModelRouting.Stop.class, () -> client.prompt().messages(List.of(message))
                .tools(new ReviewedProposalTool("{}")).advisors(advisor).call().chatResponse());
        assertEquals("PREPARED_TOOLS_CHANGED", changedTools.getMessage());
        verify(model, never()).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}
