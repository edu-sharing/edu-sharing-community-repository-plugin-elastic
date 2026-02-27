package org.edu_sharing.elasticsearch.tracker.debug;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DebugTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.debug")
    public AlfTransactionTrackerProperties debugTrackerProps() {
        return new AlfTransactionTrackerProperties();
    }
}
