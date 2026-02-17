package org.edu_sharing.elasticsearch.tracker.cascade;

import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CascadeConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.cascade")
    public BaseTrackerProperties cascadeTrackerProps() {
        return new BaseTrackerProperties();
    }
}
