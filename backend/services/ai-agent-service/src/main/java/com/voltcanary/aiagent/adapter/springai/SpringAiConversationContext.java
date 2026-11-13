package com.voltcanary.aiagent.adapter.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Lossless role projection of the already authorized, bounded durable history. No second memory store. */
public final class SpringAiConversationContext {
    private SpringAiConversationContext() { }

    public static List<Message> messages(ObjectMapper mapper, String instructions, String input) throws Exception {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(instructions));
        if (!input.stripLeading().startsWith("{")) {
            messages.add(new UserMessage(input));
            return List.copyOf(messages);
        }
        var request = mapper.readTree(input);
        if (!(request instanceof ObjectNode object) || !Set.of("planner.request", "step.request").contains(request.path("kind").asText())) {
            messages.add(new UserMessage(input));
            return List.copyOf(messages);
        }
        var dialogue = request.path("kind").asText().equals("planner.request") ? request.path("dialogue") : request.at("/context/dialogue");
        for (var item : dialogue.path("messages")) {
            String role = item.path("role").asText();
            if (!Set.of("USER", "ASSISTANT").contains(role) || !item.path("text").isTextual())
                throw new IllegalArgumentException("INVALID_DURABLE_DIALOGUE_ROLE");
            var content = mapper.createObjectNode().put("messageId", item.path("messageId").asText()).put("text", item.path("text").asText());
            messages.add(role.equals("USER") ? new UserMessage(content.toString()) : new AssistantMessage(content.toString()));
            // Retain source identities/metadata for facts and exact question references without duplicating text.
            ((ObjectNode) item).remove("text");
        }
        messages.add(new UserMessage(object.toString()));
        return List.copyOf(messages);
    }
}
