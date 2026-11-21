package com.voltcanary.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.JdbcAttemptLedger;
import com.voltcanary.aiagent.adapter.JdbcIntentPlanStore;
import com.voltcanary.aiagent.adapter.JdbcCollaborationStore;
import com.voltcanary.aiagent.adapter.JdbcContextHistory;
import com.voltcanary.aiagent.adapter.JdbcContextCursors;
import com.voltcanary.aiagent.adapter.JdbcPrivateKnowledge;
import com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders;
import com.voltcanary.aiagent.adapter.ScopedReviewContext;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Readers and persistence remain available independently of opt-in generation/context/domain wiring. */
@Configuration(proxyBeanMethods = false)
public class OrchestrationPersistenceConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.ai.knowledge.enabled", havingValue = "true")
    PrivateKnowledge privateKnowledge(JdbcTemplate jdbc) {
        var bypassesRowSecurity = jdbc.queryForObject("""
                SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user
                """, Boolean.class);
        if (!Boolean.FALSE.equals(bypassesRowSecurity)) {
            throw new IllegalStateException("Private knowledge requires a PostgreSQL role subject to row-level security");
        }
        var adapter = new JdbcPrivateKnowledge(new NamedParameterJdbcTemplate(jdbc));
        return new PrivateKnowledge(adapter, adapter, adapter);
    }

    @Bean
    Clock assistantClock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcIntentPlanStore intentPlanStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
            ProtocolValidator validator, ConversationProperties properties, JdbcCollaborationStore collaboration, Clock clock) {
        return new JdbcIntentPlanStore(jdbc, tx, mapper, validator, clock, properties.getLease(), collaboration);
    }

    @Bean
    JdbcCollaborationStore collaborationStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        return new JdbcCollaborationStore(jdbc, tx, mapper);
    }

    @Bean
    AttemptLedger attemptLedger(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Clock clock) {
        return new JdbcAttemptLedger(jdbc, tx, mapper, clock);
    }

    @Bean
    ContextSources.History contextHistory(JdbcTemplate jdbc, Clock clock) {
        return new JdbcContextHistory(jdbc, clock);
    }

    @Bean
    JdbcContextCursors contextCursors(JdbcTemplate jdbc) {
        return new JdbcContextCursors(jdbc);
    }

    @Bean
    ScopedDomainContextLoaders domainContextLoaders(ObjectMapper mapper, ProtocolValidator validator,
            AuthorizedPlanningContext authority, AiAgentProperties properties, org.springframework.web.client.RestClient.Builder builder, Clock clock) {
        return new ScopedDomainContextLoaders(mapper, validator, authority, properties, builder, clock);
    }

    @Bean
    ContextSources.Reviews reviewedContext(PlanStore reviews, IntentPlanStore plans, AuthorizedPlanningContext authority,
            ProtocolValidator validator, ObjectMapper mapper) {
        return new ScopedReviewContext(reviews, plans, authority, validator, mapper);
    }
}
