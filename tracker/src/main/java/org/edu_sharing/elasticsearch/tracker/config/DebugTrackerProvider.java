package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.DebugTracker;
import org.springframework.stereotype.Component;

@Component("debug")
public class DebugTrackerProvider implements TrackerProvider<DebugTracker> {

    @Override
    public DebugTracker create() {
        return new DebugTracker();
    }
}
