package com.voltcanary.analytics;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import com.voltcanary.analytics.service.MlTrainingLeases;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlTrainingLeaseBoundaryTest {
    @Test
    void oneClaimWinsAndAnExpiredOwnerCannotWriteAfterRecovery() throws Exception {
        var configuration = new Configuration().addAnnotatedClass(MlTrainingRun.class);
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        try (var factory = configuration.buildSessionFactory()) {
            var manager = new JpaTransactionManager(factory);
            var transactions = new TransactionTemplate(manager);
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var repository = new JpaRepositoryFactory(entityManager).getRepository(MlTrainingRunRepository.class);
            var proxy = new ProxyFactory(new MlTrainingLeases(repository, entityManager));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var leases = (MlTrainingLeases) proxy.getProxy();
            var run = new MlTrainingRun();
            run.setId(UUID.randomUUID()); run.setAccountId(UUID.randomUUID()); run.setDatasetVersionId(UUID.randomUUID());
            run.setModelFamily(MlModelFamily.ANOMALY_DETECTION); run.setAlgorithm("test"); run.setHyperparametersJson("{}");
            run.setStatus(MlTrainingRunStatus.QUEUED); run.setCreatedAt(Instant.now());
            transactions.executeWithoutResult(status -> entityManager.persist(run));
            var barrier = new CyclicBarrier(2);
            List<java.util.Optional<MlTrainingRun>> claims;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return leases.claim("a", Duration.ofMinutes(1)); });
                var second = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return leases.claim("b", Duration.ofMinutes(1)); });
                claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            }
            assertThat(claims.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            var old = claims.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
            transactions.executeWithoutResult(status -> entityManager.find(MlTrainingRun.class, run.getId())
                    .setLeaseExpiresAt(Instant.now().minusSeconds(1)));
            assertThat(leases.recover()).isEqualTo(1);
            var replacement = leases.claim("replacement", Duration.ofMinutes(1)).orElseThrow();
            old.setStatus(MlTrainingRunStatus.FAILED);
            assertThatThrownBy(() -> leases.finish(old, () -> { throw new AssertionError("Stale candidate registration"); }))
                    .isInstanceOf(MlTrainingLeases.Lost.class);
            assertThat(repository.findById(replacement.getId()).orElseThrow().getLeaseOwner()).isEqualTo("replacement");
        }
    }
}
