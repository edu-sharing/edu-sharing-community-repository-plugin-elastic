package org.edu_sharing.elasticsearch.tracker.collection;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CollectionSyncConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.collection")
    public AlfTransactionTrackerProperties collectionSyncTrackerProps() {
        return new AlfTransactionTrackerProperties();
    }
}
