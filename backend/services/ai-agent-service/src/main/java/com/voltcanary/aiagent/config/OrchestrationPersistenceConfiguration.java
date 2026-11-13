package com.voltcanary.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.JdbcAttemptLedger;
import com.voltcanary.aiagent.adapter.JdbcIntentPlanStore;
import com.voltcanary.aiagent.adapter.JdbcContextHistory;
import com.voltcanary.aiagent.adapter.JdbcContextCursors;
import com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders;
import com.voltcanary.aiagent.adapter.ScopedReviewContext;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Readers and persistence remain available independently of opt-in generation/context/domain wiring. */
@Configuration(proxyBeanMethods = false)
public class OrchestrationPersistenceConfiguration {
    @Bean
    IntentPlanStore intentPlanStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
            ProtocolValidator validator, ConversationProperties properties) {
        return new JdbcIntentPlanStore(jdbc, tx, mapper, validator, properties);
    }

    @Bean
    AttemptLedger attemptLedger(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        return new JdbcAttemptLedger(jdbc, tx, mapper);
    }

    @Bean
    ContextSources.History contextHistory(JdbcTemplate jdbc) {
        return new JdbcContextHistory(jdbc, java.time.Clock.systemUTC());
    }

    @Bean
    JdbcContextCursors contextCursors(JdbcTemplate jdbc) {
        return new JdbcContextCursors(jdbc);
    }

    @Bean
    ScopedDomainContextLoaders domainContextLoaders(ObjectMapper mapper, ProtocolValidator validator,
            AuthorizedPlanningContext authority, AiAgentProperties properties, org.springframework.web.client.RestClient.Builder builder) {
        return new ScopedDomainContextLoaders(mapper, validator, authority, properties, builder, java.time.Clock.systemUTC());
    }

    @Bean
    ContextSources.Reviews reviewedContext(PlanStore reviews, IntentPlanStore plans, AuthorizedPlanningContext authority,
            ProtocolValidator validator, ObjectMapper mapper) {
        return new ScopedReviewContext(reviews, plans, authority, validator, mapper);
    }
}
