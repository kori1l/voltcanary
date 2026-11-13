package com.voltcanary.aiagent;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LangGraphPlanPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private IntentScenarioSupport fixture() throws Exception {
        String schema = "graph_" + UUID.randomUUID().toString().replace("-", "");
        var root = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new JdbcTemplate(root).execute("create schema " + schema);
        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        return new IntentScenarioSupport(new DriverManagerDataSource(postgres.getJdbcUrl() + separator + "currentSchema=" + schema,
                postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void persistedQuestionAndGraphResumeSurviveAReplicaRestart() throws Exception {
        LangGraphAgentIntegrationTest.verifyQuestionRestart(fixture());
    }

    @Test
    void graphCheckpointAndQuestionRollbackAsOnePostgresWrite() throws Exception {
        LangGraphAgentIntegrationTest.verifyAtomicRollback(fixture());
    }

    @Test
    void concurrentResumeHasOnePlannerAndOneCheckpointOwner() throws Exception {
        LangGraphAgentIntegrationTest.verifyConcurrentResume(fixture());
    }
}
