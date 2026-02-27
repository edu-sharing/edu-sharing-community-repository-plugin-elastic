package org.edu_sharing.elasticsearch.tracker.auth;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthTrackerConfig {
    @Bean
    @ConfigurationProperties(prefix = "tracker.authorities")
    public AlfTransactionTrackerProperties authoritiesTrackerProps() {
        return new AlfTransactionTrackerProperties();
    }
}
