package com.voltcanary.alert.notification.config;

import com.voltcanary.alert.config.AlertEscalationProperties;
import com.voltcanary.alert.config.AlertRetentionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties({
        AlertNotificationProperties.class,
        AlertEscalationProperties.class,
        AlertRetentionProperties.class
})
public class NotificationAsyncConfig {

    @Bean(name = "alertNotificationExecutor")
    ThreadPoolTaskExecutor alertNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("alert-notification-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.initialize();
        return executor;
    }
}
