package org.edu_sharing.elasticsearch.tracker.preview;

import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PreviewTrackerConfig {
    @Bean
    @ConfigurationProperties(prefix = "tracker.preview")
    public AlfTransactionTrackerProperties previewTrackerProps() {
        return new AlfTransactionTrackerProperties();
    }
}
