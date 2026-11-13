package com.voltcanary.analytics.vcl;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CompilationBundleH2Test extends BundleStoreContract {
    private static final DataSource SOURCE = new DriverManagerDataSource(
            "jdbc:h2:mem:vcl-bundle-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");

    @BeforeAll
    static void migrate() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql")).execute(SOURCE);
    }

    @Override DataSource dataSource() { return SOURCE; }
}
