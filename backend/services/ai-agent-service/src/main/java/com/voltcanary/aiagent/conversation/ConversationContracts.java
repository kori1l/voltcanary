package com.voltcanary.aiagent.conversation;

import com.voltcanary.aiagent.dto.DashboardTurnMetadata;
import com.voltcanary.aiagent.dto.PlanResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConversationContracts {

    private ConversationContracts() {}

    public record Create(@NotNull UUID conversationId) {}

    public record Selection(@NotNull UUID conversationId) {}

    public record Conversation(
        UUID id,
        String title,
        long revision,
        UUID lastPlanId,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
    ) {}

    public record Message(
        UUID id,
        long sequence,
        UUID requestId,
        String role,
        String kind,
        String text,
        boolean redacted,
        boolean automatic,
        UUID planId,
        UUID taskId,
        UUID turnId,
        String outcome,
        Instant createdAt
    ) {}

    public record Page<T>(List<T> items, String nextCursor) {}

    public record History(Page<Conversation> conversations, UUID activeConversationId) {}

    public record Detail(
        Conversation conversation,
        PlanResponse plan,
        String resourceStatus,
        String pendingStatus,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
        )
        com.fasterxml.jackson.databind.JsonNode orchestration,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
        )
        PlanResponse acceptedReview
    ) {
        public Detail(
            Conversation conversation,
            PlanResponse plan,
            String resourceStatus,
            String pendingStatus,
            com.fasterxml.jackson.databind.JsonNode orchestration
        ) {
            this(conversation, plan, resourceStatus, pendingStatus, orchestration, null);
        }

        public Detail(
            Conversation conversation,
            PlanResponse plan,
            String resourceStatus,
            String pendingStatus
        ) {
            this(conversation, plan, resourceStatus, pendingStatus, null);
        }
    }

    public record Submit(
        @NotNull UUID requestId,
        @PositiveOrZero long expectedRevision,
        @NotBlank @Size(max = 2000) String text,
        Map<String, Object> context,
        UUID parentPlanId,
        UUID providerId,
        UUID modelConfigId,
        DashboardTurnMetadata metadata,
        boolean automatic,
        @Size(max = 2000) String displayText,
        @Size(min = 1, max = 200) String continueStepId
    ) {
        public Submit(
            UUID requestId,
            long expectedRevision,
            String text,
            Map<String, Object> context,
            UUID parentPlanId,
            UUID providerId,
            UUID modelConfigId,
            DashboardTurnMetadata metadata,
            boolean automatic,
            String displayText
        ) {
            this(
                requestId,
                expectedRevision,
                text,
                context,
                parentPlanId,
                providerId,
                modelConfigId,
                metadata,
                automatic,
                displayText,
                null
            );
        }
    }
}
