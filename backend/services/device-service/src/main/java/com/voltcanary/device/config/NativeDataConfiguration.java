package com.voltcanary.device.config;

import com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog;
import com.voltcanary.device.configurable.port.NativeDatasetCatalog;
import com.voltcanary.device.configurable.port.NativeCollectionReviewStore;
import com.voltcanary.device.configurable.adapter.JdbcCollectionReviews;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowMutations;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowQueries;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowReviews;
import com.voltcanary.device.configurable.adapter.NativeQueryCursor;
import com.voltcanary.device.configurable.port.NativeRowMutations;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.device.configurable.port.NativeRowReviews;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(NativeDependencyProperties.class)
public class NativeDataConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.native-data.retention-enabled", havingValue = "true")
    com.voltcanary.device.configurable.adapter.NativeGenerationRetention nativeGenerationRetention(DataSource source, Clock clock,
            @Value("${app.native-data.retention-grace:30d}") java.time.Duration grace) {
        return new com.voltcanary.device.configurable.adapter.NativeGenerationRetention(source, clock, grace);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeHistoryLeases nativeHistoryLeases(DataSource source, Clock clock) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeHistoryLeases(source, clock);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeHistoryRows nativeHistoryRows(DataSource source, NativeQueryCursor cursors,
            com.voltcanary.device.configurable.port.NativeWorkspaceIdentity identity) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeHistoryRows(source, cursors, identity);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeWorkspaceIdentity nativeWorkspaceIdentity(com.voltcanary.device.workspace.CurrentWorkspaceMembership membership,
            com.voltcanary.device.workspace.WorkspaceResourceAccess resources) {
        return new com.voltcanary.device.configurable.adapter.WorkspaceNativeIdentity(membership, resources);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeSharing nativeSharing(DataSource source, Clock clock,
            com.voltcanary.device.configurable.port.NativeWorkspaceIdentity identity) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeSharing(source, clock, identity);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeSharedRowChanges nativeSharedRowChanges(DataSource source, Clock clock,
            com.voltcanary.device.configurable.port.NativeWorkspaceIdentity identity) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeSharedRowChanges(source, clock, identity);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeSharedRows nativeSharedRows(DataSource source, NativeQueryCursor cursors,
            com.voltcanary.device.configurable.port.NativeWorkspaceIdentity identity) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeSharedRows(source, cursors, identity);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeSchemaMigrations nativeSchemaMigrations(DataSource source, Clock clock,
            com.voltcanary.device.configurable.port.NativeMigrationDependencies dependencies) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeSchemaMigrations(source, clock, dependencies);
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeMigrationDependencies nativeMigrationDependencies(
            DataSource source, NativeDependencyProperties properties, InternalServiceProperties internal) {
        return new com.voltcanary.device.configurable.adapter.HttpNativeMigrationDependencies(source, properties, internal.getServiceToken());
    }

    @Bean
    com.voltcanary.device.configurable.port.NativeImports nativeImports(DataSource source, Clock clock) {
        return new com.voltcanary.device.configurable.adapter.JdbcNativeImports(source, clock);
    }

    @Bean
    NativeDatasetCatalog nativeDatasetCatalog(DataSource source) { return new JdbcNativeDatasetCatalog(source); }

    @Bean
    Clock nativeDataClock() { return Clock.systemUTC(); }

    @Bean
    NativeCollectionReviewStore nativeCollectionReviewStore(DataSource source, Clock clock) { return new JdbcCollectionReviews(source, clock); }

    @Bean
    NativeRowReviews nativeRowReviews(DataSource source, Clock clock) {
        return new JdbcNativeRowReviews(source, clock);
    }

    @Bean
    NativeRowMutations nativeRowMutations(DataSource source, Clock clock) {
        return new JdbcNativeRowMutations(source, clock);
    }

    @Bean
    NativeQueryCursor nativeQueryCursor(Clock clock, @Value("${app.native-data.cursor-key:}") String encoded) {
        byte[] key;
        if (encoded.isBlank()) {
            key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
        } else {
            try { key = java.util.Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid native cursor signing key encoding"); }
        }
        return new NativeQueryCursor(key, clock);
    }

    @Bean
    NativeRowQueries nativeRowQueries(DataSource source, NativeQueryCursor cursors) {
        return new JdbcNativeRowQueries(source, cursors);
    }
}
