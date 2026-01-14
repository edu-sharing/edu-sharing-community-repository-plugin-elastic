package org.edu_sharing.elasticsearch.tracker.generic;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "generic")
public class TrackerProperties {

    private Map<String, TrackerConfig> trackers = new HashMap<>();

    @Data
    public static class TrackerConfig {
        private String provider;
        private String includeNodeTypes, excludeNodeTypes;
        private int transactions;
        private long interval;
        private Duration timeStep;
    }
}
