package com.voltcanary.identity.config;

import com.voltcanary.identity.repository.JdbcLoginProtectionStore;
import com.voltcanary.identity.service.LoginProtectionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class LoginProtectionConfiguration {

    @Bean
    LoginProtectionStore loginProtectionStore(
        JdbcTemplate jdbc,
        PlatformTransactionManager transactions,
        IdentityAuthProperties properties
    ) {
        return new JdbcLoginProtectionStore(jdbc, transactions, properties);
    }
}
