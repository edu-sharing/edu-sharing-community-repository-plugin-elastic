package org.edu_sharing.elasticsearch.tracker.statistics;

import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StatisticsConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.statistics")
    public BaseTrackerProperties statisticTrackerProps() {
        return new BaseTrackerProperties();
    }
}
