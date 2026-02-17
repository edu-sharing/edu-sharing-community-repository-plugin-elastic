package org.edu_sharing.elasticsearch.tracker.core.config;

import lombok.Data;

import java.time.Duration;
import java.util.List;

@Data
public class BaseTrackerProperties {
    private boolean enabled = true;
    private TrackerSchedulerSettings scheduler = new TrackerSchedulerSettings();
    private List<String> dependsOn = List.of();
}
