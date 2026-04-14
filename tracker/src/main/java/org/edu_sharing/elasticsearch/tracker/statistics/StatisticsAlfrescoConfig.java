package org.edu_sharing.elasticsearch.tracker.statistics;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StatisticsAlfrescoConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.statisticsalfresco")
    public AlfTransactionTrackerProperties statisticsAlfrescoTrackerProperties() {
        return new AlfTransactionTrackerProperties();
    }
}
