package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;

public interface TrackerCoroutineConfig {
    Tracker getTracker();
    BaseTrackerProperties getConfig();
}
