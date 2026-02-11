package org.edu_sharing.elasticsearch.tracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "config")
public class TrackerProperties {

    private Map<String, TrackerConfig> tracker = new LinkedHashMap<>();

    @Data
    public static class TrackerConfig {
        private String provider;
        private List<String> includeNodeTypes, excludeNodeTypes;
        private List<String> includeAspects, excludeAspects;
        private int transactions = 200;
        private long interval = 5000;
        private Duration timeStep;
        private List<String> trackerDependency;
        private String storeProtocol;
        private String storeIdentifier;
    }
}
