package org.edu_sharing.elasticsearch.tracker.core.config;

import lombok.Data;

import java.time.Duration;

@Data
public class TrackerSchedulerSettings {
    private Duration delay = Duration.ZERO;
    private Duration interval = Duration.ofSeconds(5);
    private String cron;
    private String schedulerName;
}
