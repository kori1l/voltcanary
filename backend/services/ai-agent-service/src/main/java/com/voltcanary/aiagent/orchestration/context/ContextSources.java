package com.voltcanary.aiagent.orchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.DialogueMessagesItem;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only ports. Neither loader data nor cursors grant authority or become instructions. */
public final class ContextSources {

    private ContextSources() {}

    @FunctionalInterface
    public interface Access {
        IntentOrchestration.Skills authorize(IntentPlans.ModelInput input, Session session);
    }

    public interface History {
        long through(Session session, UUID conversation);
        long count(Session session, UUID conversation, long through);
        List<DialogueMessagesItem> required(
            Session session,
            UUID conversation,
            long through,
            Set<String> ids
        );
        List<DialogueMessagesItem> recent(
            Session session,
            UUID conversation,
            long through,
            int limit
        );
        List<DialogueMessagesItem> page(
            Session session,
            UUID conversation,
            long through,
            long after,
            int limit
        );
    }

    public record Position(long after, long through) {}

    public interface Cursors {
        String issue(String binding, Position position, Instant expiresAt);
        Position resolve(String token, String binding, Instant now);
    }

    public record Page(String snapshotId, Instant asOf, JsonNode data, Position next) {}

    public record BaseReview(String reviewId, String digest, JsonNode data) {}

    public record ModelBudget(
        com.voltcanary.aiagent.orchestration.routing.AttemptLedger ledger,
        com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy policy,
        String stepId,
        UUID callId
    ) {}

    @FunctionalInterface
    public interface Reviews {
        BaseReview read(IntentPlans.ModelInput input, Session session);
    }

    @FunctionalInterface
    public interface Initial {
        com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.LoadedContext read(
            IntentPlans.ModelInput input,
            Session session
        );

        default boolean includesBaseReview(IntentPlans.ModelInput input) {
            return true;
        }

        default List<com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Choice> plannerChoices(
            IntentPlans.ModelInput input,
            Session session
        ) {
            return List.of();
        }
    }

    public interface Loader {
        String id();
        JsonNode resultSchema();

        default Page initial(
            IntentPlans.ModelInput input,
            Session session,
            java.util.function.Supplier<JsonNode> submitted
        ) {
            return null;
        }

        default JsonNode initialSchema() {
            return null;
        }

        default void verifyForDispatch(
            IntentPlans.ModelInput input,
            Session session,
            JsonNode data
        ) {}

        default void verifyInitialForDispatch(
            IntentPlans.ModelInput input,
            Session session,
            JsonNode data
        ) {}

        Page load(
            IntentPlans.ModelInput input,
            Session session,
            JsonNode arguments,
            Position position
        );

        default Page load(
            IntentPlans.ModelInput input,
            Session session,
            JsonNode arguments,
            Position position,
            JsonNode previousResults
        ) {
            return load(input, session, arguments, position);
        }

        default Page load(
            IntentPlans.ModelInput input,
            Session session,
            JsonNode arguments,
            Position position,
            JsonNode previousResults,
            ModelBudget budget
        ) {
            return load(input, session, arguments, position, previousResults);
        }
    }
}
