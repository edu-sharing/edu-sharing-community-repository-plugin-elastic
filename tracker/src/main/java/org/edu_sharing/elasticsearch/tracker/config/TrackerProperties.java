package org.edu_sharing.elasticsearch.tracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
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
        private List<String> includeNodeTypes = new ArrayList<>();
        private List<String> excludeNodeTypes = new ArrayList<>();
        private List<String> includeAspects = new ArrayList<>();
        private List<String> excludeAspects =  new ArrayList<>();
        private int transactions = 200;
        private long interval = 5000;
        private Duration timeStep;
        private List<String> trackerDependency = new ArrayList<>();
        private String storeProtocol;
        private String storeIdentifier;
    }
}
