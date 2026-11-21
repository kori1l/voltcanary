package com.voltcanary.device.configurable;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

final class NativeTestMigrations {
    private NativeTestMigrations() { }

    static void apply(DataSource source) {
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V017__workspace_resource_grants.sql"),
                new ClassPathResource("db/migration/V024__configurable_native_datasets.sql"),
                new ClassPathResource("db/migration/V025__native_collection_reviews.sql"),
                new ClassPathResource("db/migration/V026__native_row_reviews.sql"),
                new ClassPathResource("db/migration/V028__native_query_snapshots.sql"),
                new ClassPathResource("db/migration/V033__native_imports.sql"),
                new ClassPathResource("db/migration/V034__native_generations.sql"),
                new ClassPathResource("db/migration/V035__native_chunked_imports.sql"),
                new ClassPathResource("db/migration/V036__native_schema_migrations.sql"),
                new ClassPathResource("db/migration/V037__native_access_grants.sql"),
                new ClassPathResource("db/migration/V038__native_history_cuts.sql"),
                new ClassPathResource("db/migration/V039__native_history_leases.sql"),
                new ClassPathResource("db/migration/V040__native_generation_retention.sql"),
                new ClassPathResource("db/migration/V041__native_import_worker_retry.sql")).execute(source);
    }
}
