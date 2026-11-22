package com.voltcanary.analytics.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The materialization commit starts only after remote telemetry has been collected and validated. */
@Service
public class MlDatasetWrites {

    private final TransactionTemplate transaction;

    public MlDatasetWrites(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    public <T> T commit(Supplier<T> write) {
        return transaction.execute(status -> write.get());
    }
}
