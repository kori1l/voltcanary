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
public class NativeDataConfiguration {
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
