package org.edu_sharing.elasticsearch.tracker.suggestions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SuggestionTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.suggestion")
    public AlfTransactionTrackerProperties suggestionTrackerProperties() {
        return new AlfTransactionTrackerProperties();
    }
}
