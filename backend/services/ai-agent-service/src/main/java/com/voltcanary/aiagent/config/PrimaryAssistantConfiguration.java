package com.voltcanary.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders;
import com.voltcanary.aiagent.adapter.ExistingOrchestrationReceipts;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.context.ContextLoading;
import com.voltcanary.aiagent.orchestration.context.ConversationHistoryLoader;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder;
import com.voltcanary.aiagent.port.AssistantTraces;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.DashboardPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.aiagent.service.PrimaryDomainPreparation;
import com.voltcanary.aiagent.service.PrimaryDomain;
import com.voltcanary.aiagent.nativeops.NativePrimaryDomain;
import com.voltcanary.aiagent.nativeops.NativeTableWorkflow;
import com.voltcanary.aiagent.service.PrimaryRuntime;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import com.voltcanary.aiagent.service.PrimaryAssistant;
import com.voltcanary.aiagent.service.LegacyAssistantContinuation;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PrimaryAssistantConfiguration {
    @Bean
    com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner agentGraphRunner() {
        return new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner();
    }

    @Bean
    PrimaryAssistant primaryAssistant(PrimaryRuntime runtime, PrimaryOrchestrationJournal journal, IntentPlanStore plans, ConversationStore conversations,
            AiAgentOrchestrator reviews, AiModelResolver models, AiModelConfigRepository configs, AuthorizedPlanningContext authority,
            ObjectMapper mapper, PrimaryAssistantProperties properties, LegacyAssistantContinuation legacy) {
        return new PrimaryAssistant(new PrimaryAssistant.Ports(runtime, journal, plans, conversations, reviews, models, configs, authority, mapper, properties, legacy));
    }
    @Bean
    LegacyAssistantContinuation legacyAssistantContinuation(PlanStore reviews, ConversationStore conversations, ObjectMapper mapper,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        return new LegacyAssistantContinuation(reviews, conversations, mapper, transactions);
    }
    @Bean
    PrimaryOrchestrationJournal primaryJournal(JdbcTemplate jdbc, IntentPlanStore plans, ObjectMapper mapper) {
        return new PrimaryOrchestrationJournal(jdbc, plans, mapper);
    }
    @Bean
    PrimaryDomainPreparation primaryDomain(ObjectMapper mapper, ProtocolValidator validator, IntentPlanStore plans, PlanStore reviews,
            AuthorizedPlanningContext authority, DashboardPlanningContext dashboards, AiAgentProperties properties, RestClient.Builder builder,
            PrimaryOrchestrationJournal journal, JdbcTemplate jdbc) {
        return new PrimaryDomainPreparation(mapper, validator, plans, reviews, authority, dashboards, properties, builder, journal, jdbc);
    }
    @Bean
    PrimaryDomain primaryDomainExtensions(PrimaryDomainPreparation existing, NativeTableWorkflow nativeTables, IntentPlanStore plans, com.voltcanary.aiagent.vehicle.VehicleMonitoringOwner vehicleOwner,
            ObjectMapper mapper, ProtocolValidator validator, PlanStore reviews, AuthorizedPlanningContext authority, PrimaryOrchestrationJournal journal) {
        return new com.voltcanary.aiagent.vehicle.VehiclePrimaryDomain(new NativePrimaryDomain(existing, nativeTables, plans), vehicleOwner, mapper, validator, plans, reviews, authority, journal);
    }
    @Bean
    PrimaryRuntime primaryRuntime(ObjectMapper mapper, ProtocolValidator validator, IntentPlanStore plans, AttemptLedger ledger,
            ContextSources.History history, ContextSources.Reviews reviews, ContextSources.Cursors cursors, ScopedDomainContextLoaders scoped,
            @org.springframework.beans.factory.annotation.Qualifier("primaryDomainExtensions") PrimaryDomain domain,
            AiAgentOrchestrator executor, AuthorizedPlanningContext authority, PlanStore reviewStore,
            AssistantTraces traces, AiModelResolver resolver, AiAgentModelRunRecorder runs, PrimaryAssistantProperties properties,
            com.voltcanary.aiagent.service.WidgetCatalogueSearch catalogueSearch,
            com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner graphRunner,
            com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore collaboration,
            io.micrometer.observation.ObservationRegistry observations, Clock clock,
            org.springframework.beans.factory.ObjectProvider<com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge> knowledge,
            WidgetEmbeddingProperties embeddings, com.voltcanary.aiagent.nativeops.NativeOwnerClient nativeOwner,
            @org.springframework.beans.factory.annotation.Value("${app.ai.knowledge.vector-generation:}") String vectorGeneration) {
        var definitions = new ArrayList<>(scoped.definitions());
        if (properties.isNativeDataEnabled()) {
            definitions.add(new com.voltcanary.aiagent.nativeops.NativeSourceCaptureLoader(nativeOwner, mapper, clock).definition());
        }
        knowledge.ifAvailable(index -> {
            if (!vectorGeneration.isBlank() && !embeddings.enabled()) {
                throw new IllegalStateException("Private knowledge vector search requires configured embeddings");
            }
            var generation = vectorGeneration.isBlank() ? null : java.util.UUID.fromString(vectorGeneration);
            var embedder = generation == null ? null : new com.voltcanary.aiagent.adapter.springai.SpringAiCapabilityEmbeddings(embeddings, observations);
            definitions.add(com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader.definition(mapper, clock, collaboration,
                    index, generation, embedder));
            definitions.add(com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader.resolveDefinition(mapper, clock, collaboration, index));
        });
        var registry = new SkillRegistry(validator, PrimarySkillPackages.build(mapper, validator, definitions));
        var loaders = new ArrayList<ContextSources.Loader>();
        definitions.forEach(def -> loaders.add(def.loader()));
        loaders.add(domain);
        loaders.add(new ConversationHistoryLoader(history, mapper, validator, clock));
        var loading = new ContextLoading(registry, validator, cursors, loaders, clock);
        var receipts = new ExistingOrchestrationReceipts(reviewStore, executor, authority, mapper);
        return new PrimaryRuntime(new PrimaryRuntime.Infrastructure(mapper, validator, plans, ledger, history, reviews, loading, receipts, traces, resolver, runs, graphRunner, collaboration, authority, observations, clock), registry, domain, properties.getProfile(), catalogueSearch);
    }
}
