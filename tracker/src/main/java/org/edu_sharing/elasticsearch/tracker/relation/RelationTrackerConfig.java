package org.edu_sharing.elasticsearch.tracker.relation;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RelationTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.relation")
    public AlfTransactionTrackerProperties relationTrackerProperties() {
        return new AlfTransactionTrackerProperties();
    }
}
