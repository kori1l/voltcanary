package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PriceSnapshot;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

/** Uses the existing scoped registry/credential resolution; never changes account settings. */
public final class ExistingModelAvailability implements ModelRouting.Availability {

    public record Qualification(
        String id,
        String modelConfigId,
        String profileId,
        String intentId,
        String phase
    ) {}

    private final AiModelResolver resolver;
    private final AuthenticatedUser user;
    private final Map<String, Set<String>> modes;
    private final Set<Qualification> qualifications;
    private final Map<String, PriceSnapshot> prices;

    public ExistingModelAvailability(
        AiModelResolver resolver,
        AuthenticatedUser user,
        Map<String, Set<String>> modes,
        Set<Qualification> qualifications,
        Map<String, PriceSnapshot> prices
    ) {
        this.resolver = resolver;
        this.user = user;
        var copy = new java.util.HashMap<String, Set<String>>();
        modes.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        this.modes = Map.copyOf(copy);
        this.qualifications = Set.copyOf(qualifications);
        var mapper = new ObjectMapper();
        var validator = new ProtocolValidator(mapper);
        prices.forEach((id, price) -> {
            validator.validate("priceSnapshot", mapper.valueToTree(price));
            if (!id.equals(price.priceSnapshotId())) throw new ModelRouting.Stop(
                "PRICE_ID_MISMATCH"
            );
        });
        this.prices = Map.copyOf(prices);
    }

    @Override
    public boolean authorized(String providerId, String modelConfigId) {
        try {
            return resolver
                .resolve(user, UUID.fromString(providerId), UUID.fromString(modelConfigId))
                .isPresent();
        } catch (ResponseStatusException unavailable) {
            if (unavailable.getStatusCode().value() == 404) return false;
            throw unavailable;
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }

    @Override
    public boolean qualified(
        String qualificationId,
        String model,
        String profile,
        String intent,
        String phase
    ) {
        return qualifications.contains(
            new Qualification(qualificationId, model, profile, intent, phase)
        );
    }

    @Override
    public Set<String> outputModes(String modelConfigId) {
        return modes.getOrDefault(modelConfigId, Set.of());
    }

    @Override
    public PriceSnapshot price(String snapshotId) {
        return snapshotId == null ? null : prices.get(snapshotId);
    }
}
