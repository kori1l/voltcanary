package com.voltcanary.aiagent.adapter.springai;

import com.voltcanary.aiagent.orchestration.routing.DispatchPermit;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

record ReservedDispatchAdvisor(DispatchPermit permit) implements CallAdvisor {
    @Override public String getName() { return "ReservedDispatchAdvisor"; }
    @Override public int getOrder() { return 100; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        permit.consume();
        return chain.nextCall(request);
    }
}
