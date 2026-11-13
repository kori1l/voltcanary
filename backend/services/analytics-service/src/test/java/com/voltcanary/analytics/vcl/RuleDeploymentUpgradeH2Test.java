package com.voltcanary.analytics.vcl;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RuleDeploymentUpgradeH2Test extends RuleDeploymentUpgradeContract {
    private final DataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:vcl-upgrade-" + java.util.UUID.randomUUID()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
    @Override DataSource source() { return source; }
}
