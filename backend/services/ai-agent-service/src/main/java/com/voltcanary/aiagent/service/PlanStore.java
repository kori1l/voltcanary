package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentPlan;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PlanStore {

    private final Map<UUID, AgentPlan> plans = new ConcurrentHashMap<>();

    public void save(AgentPlan plan) {
        plans.put(plan.id(), plan);
    }

    public Optional<AgentPlan> find(UUID planId) {
        return Optional.ofNullable(plans.get(planId));
    }
}
