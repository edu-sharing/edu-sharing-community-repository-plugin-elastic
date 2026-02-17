package org.edu_sharing.elasticsearch.tracker.main;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MainTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.main")
    public AlfTransactionTrackerProperties mainTrackerProperties() {
        return new AlfTransactionTrackerProperties();
    }
}
