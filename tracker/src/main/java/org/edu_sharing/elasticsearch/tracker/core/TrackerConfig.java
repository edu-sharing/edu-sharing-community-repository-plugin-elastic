package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;

public interface TrackerConfig<PROPS extends BaseTrackerProperties, STATUS> {
    Tracker<STATUS> getTracker();

    PROPS getConfig();

    Class<STATUS> getStatusClass();

    String getName();

    void setName(String name);

}
