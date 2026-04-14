package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;

public interface TrackerConfig<PROPS extends BaseTrackerProperties, STATUS> extends TrackerScheduleConfig<PROPS, STATUS> {
    Class<STATUS> getStatusClass();
}
