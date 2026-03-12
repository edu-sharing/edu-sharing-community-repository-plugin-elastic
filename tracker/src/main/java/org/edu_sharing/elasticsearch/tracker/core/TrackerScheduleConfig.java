package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.tracker.core.config.TrackerScheduleProperties;

public interface TrackerScheduleConfig<PROPS extends TrackerScheduleProperties, STATUS> {
    Tracker<STATUS> getTracker();

    PROPS getConfig();

    String getName();

    void setName(String name);
}
