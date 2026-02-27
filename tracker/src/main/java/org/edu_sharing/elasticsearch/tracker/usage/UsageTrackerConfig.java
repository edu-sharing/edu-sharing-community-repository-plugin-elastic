package org.edu_sharing.elasticsearch.tracker.usage;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsageTrackerConfig {
    @Bean
    @ConfigurationProperties(prefix = "tracker.usage")
    public AlfTransactionTrackerProperties usageTrackerProps() {
        return new AlfTransactionTrackerProperties();
    }
}
