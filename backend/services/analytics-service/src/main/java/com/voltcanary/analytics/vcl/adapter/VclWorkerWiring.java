package com.voltcanary.analytics.vcl.adapter;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The diagnostic scheduler remains opt-in; owner adapters can be reused by later worker stages. */
@Configuration
public class VclWorkerWiring {
    @Bean
    @ConditionalOnProperty(prefix = "app.vcl.worker", name = "enabled", havingValue = "true")
    WorkerPausedWindows workerPausedWindows(DataSource source) {
        return new WorkerPausedWindows(source);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.vcl.worker", name = "enabled", havingValue = "true")
    WorkerJournalWindowOpening workerJournalWindowOpening(DataSource source, WorkerRuleCompilationSnapshots snapshots) {
        return new WorkerJournalWindowOpening(source, snapshots);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.vcl.worker", name = "alert-effects-enabled", havingValue = "true")
    WorkerWindowAlertDelivery workerWindowAlertDelivery(DataSource source, org.springframework.web.client.RestClient.Builder builder,
            com.voltcanary.analytics.config.AnalyticsDownstreamProperties downstream,
            com.voltcanary.analytics.config.VclWorkerIdentityProperties identity) {
        return new WorkerWindowAlertDelivery(source, builder, downstream, identity);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.vcl.worker", name = "enabled", havingValue = "true")
    WorkerEvaluatorReadiness workerEvaluatorReadiness(WorkerStartCheckpoints checkpoints) {
        return new WorkerEvaluatorReadiness(checkpoints);
    }

    @Bean
    WorkerDeploymentAdmission workerDeploymentAdmission(DataSource source, WorkerGrantReads grants,
            WorkerPolicyReads policies, WorkerSourceCaptureReads owners) {
        return new WorkerDeploymentAdmission(source, grants, policies, owners);
    }

    @Bean
    WorkerRuleCompilationSnapshots workerRuleCompilationSnapshots(DataSource source, WorkerSourceCaptureSnapshots captures,
            WorkerGrantReads grants, WorkerPolicyReads policies, WorkerCircleReads circles, WorkerStartCheckpoints checkpoints) {
        return new WorkerRuleCompilationSnapshots(source, captures, grants, policies, circles, checkpoints);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.vcl.worker", name = "enabled", havingValue = "true")
    WorkerDueWindows workerDueWindows(DataSource source, WorkerDeploymentAdmission admission,
            WorkerRuleCompilationSnapshots recovery) {
        return new WorkerDueWindows(source, admission, recovery);
    }
}
