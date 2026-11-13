package com.voltcanary.aiagent.orchestration.routing;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Candidate;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PriceSnapshot;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

/** Pure policy: no natural-language routing and no provider-name rankings. */
public final class ModelRouting {
    private ModelRouting() { }

    public static final class Stop extends RuntimeException {
        public Stop(String code) { super(code); }
    }

    /** Authorization and qualification come from server-owned configuration, never model output. */
    public interface Availability {
        boolean authorized(String providerId, String modelConfigId);
        boolean qualified(String qualificationId, String modelConfigId, String profileId, String intentId, String phase);
        Set<String> outputModes(String modelConfigId);
        PriceSnapshot price(String snapshotId);
    }

    public record Size(long inputTokens, long outputTokens, long bytes, String precision) { }
    public record Route(Candidate candidate, Size size, long costMicros, PriceSnapshot price, String reason) { }

    /** The serialized body MUST include instructions, schema, actual dialogue and all mandatory data.
     * UTF-8 bytes/token plus explicit provider framing allowance is a conservative estimate.
     * Output reserve includes reasoning once, not twice. No truncation happens here.
     */
    public static Size estimate(String serializedBody, long outputIncludingReasoning, long framingAllowance) {
        if (serializedBody == null || outputIncludingReasoning <= 0 || framingAllowance < 0) throw new Stop("REQUEST_SIZE_INVALID");
        long bytes = serializedBody.getBytes(StandardCharsets.UTF_8).length;
        return new Size(Math.addExact(bytes, framingAllowance), outputIncludingReasoning, bytes, "ESTIMATED");
    }

    public static Route select(RoutingPolicy policy, String profileId, String intent, String phase, Size size,
            Availability availability, long affordableMicros, String previousModel, Instant now) {
        return select(policy, profileId, intent, phase, candidate -> size, availability, affordableMicros, previousModel, now);
    }

    public static Route select(RoutingPolicy policy, String profileId, String intent, String phase,
            java.util.function.Function<Candidate, Size> sizes, Availability availability,
            long affordableMicros, String previousModel, Instant now) {
        var profile = policy.profiles().stream().filter(item -> item.profileId().equals(profileId)
                && item.phases().contains(phase) && (phase.equals("PLANNER") || item.intentIds().contains(intent)))
                .findFirst().orElseThrow(() -> new Stop("PROFILE_UNAVAILABLE"));
        var eligible = new ArrayList<Route>();
        String failure = "MODEL_UNAVAILABLE";
        for (var candidate : profile.candidates()) {
            if (policy.mode().equals("PINNED") && !candidate.modelConfigId().equals(policy.pinnedModelConfigId())) continue;
            if (!availability.authorized(candidate.providerId(), candidate.modelConfigId())) continue;
            if (policy.mode().equals("AUTO") && !availability.qualified(candidate.qualificationId(), candidate.modelConfigId(), profileId, intent, phase)) {
                failure = "MODEL_UNQUALIFIED";
                continue;
            }
            if (previousModel != null && !previousModel.equals(candidate.modelConfigId())
                    && (policy.mode().equals("PINNED") || !profile.allowEscalation())) continue;
            if (!availability.outputModes(candidate.modelConfigId()).contains(candidate.outputMode())) {
                failure = "OUTPUT_MODE_UNSUPPORTED";
                continue;
            }
            Size size = sizes.apply(candidate);
            if (size.inputTokens() > candidate.maxInputTokens() || size.outputTokens() > candidate.maxOutputTokens()
                    || Math.addExact(size.inputTokens(), size.outputTokens()) > candidate.contextWindow()) {
                failure = "CONTEXT_INSUFFICIENT";
                continue;
            }
            PriceSnapshot price = availability.price(candidate.priceSnapshotId());
            boolean priced = usable(price, candidate, policy.turnLimits().currency(), now);
            long cost;
            if (priced) cost = cost(price, size.inputTokens(), 0, size.outputTokens(), 0);
            else if (policy.unknownPriceBehavior().equals("RESERVED_UPPER_BOUND")) cost = policy.unknownCostUpperBoundMicros();
            else { failure = "PRICE_UNAVAILABLE"; continue; }
            if (!policy.turnLimits().currency().equals(policy.planLimits().currency())) throw new Stop("CURRENCY_MISMATCH");
            if (cost > affordableMicros || cost > policy.turnLimits().maxCostMicros() || cost > policy.planLimits().maxCostMicros()) {
                failure = "NO_AFFORDABLE_MODEL";
                continue;
            }
            String reason = policy.mode().equals("PINNED") ? "PINNED_EXPLICIT" : previousModel != null
                    && !previousModel.equals(candidate.modelConfigId()) ? "AUTO_AUTHORIZED_ESCALATION" : "AUTO_LOWEST_RESERVED_COST";
            eligible.add(new Route(candidate, size, cost, priced ? price : null, reason));
        }
        String stopCode = failure;
        return eligible.stream().min(Comparator.comparingLong(Route::costMicros)
                .thenComparing(route -> route.candidate().modelConfigId())).orElseThrow(() -> new Stop(stopCode));
    }

    private static boolean usable(PriceSnapshot price, Candidate model, String currency, Instant now) {
        return price != null && price.providerId().equals(model.providerId()) && price.modelConfigId().equals(model.modelConfigId())
                && price.priceSnapshotId().equals(model.priceSnapshotId()) && price.currency().equals(currency)
                && !now.isBefore(Instant.parse(price.effectiveAt())) && now.isBefore(Instant.parse(price.expiresAt()));
    }

    public static long cost(PriceSnapshot price, long input, long cached, long output, long reasoning) {
        if (input < 0 || cached < 0 || cached > input || output < 0 || reasoning < 0
                || (price.reasoningIncludedInOutput() && reasoning > output)) throw new Stop("USAGE_INVALID");
        var rates = price.rates();
        long billableOutput = price.reasoningIncludedInOutput() ? output : Math.addExact(output, reasoning);
        var total = rates.inputMicrosPerMillion().multiply(BigDecimal.valueOf(input - cached))
                .add(rates.cachedInputMicrosPerMillion().multiply(BigDecimal.valueOf(cached)))
                .add(rates.outputMicrosPerMillion().multiply(BigDecimal.valueOf(billableOutput)))
                .divide(BigDecimal.valueOf(1_000_000)).add(rates.additionalMicrosPerCall());
        return total.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    public static Usage reconcile(PriceSnapshot price, Usage usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null
                || usage.cachedInputTokens() == null || usage.reasoningTokens() == null) return null;
        long cost = cost(price, usage.inputTokens(), usage.cachedInputTokens(), usage.outputTokens(), usage.reasoningTokens());
        return new Usage(usage.inputTokens(), usage.cachedInputTokens(), usage.outputTokens(), usage.reasoningTokens(), cost);
    }
}
