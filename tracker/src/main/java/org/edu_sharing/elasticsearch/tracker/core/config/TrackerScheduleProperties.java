package org.edu_sharing.elasticsearch.tracker.core.config;

import lombok.Data;

@Data
public class TrackerScheduleProperties {
    private boolean enabled = true;
    private TrackerSchedulerSettings scheduler = new TrackerSchedulerSettings();
}
