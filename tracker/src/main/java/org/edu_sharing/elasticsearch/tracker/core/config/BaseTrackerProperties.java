package org.edu_sharing.elasticsearch.tracker.core.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class BaseTrackerProperties extends TrackerScheduleProperties {
    private List<String> dependsOn = List.of();
}
