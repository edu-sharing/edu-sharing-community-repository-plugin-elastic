package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.UsageSyncTracker;
import org.springframework.stereotype.Component;

@Component("usageSyncTrackerProvider")
public class UsageSyncTrackerProvider implements TrackerProvider<UsageSyncTracker> {
    @Override
    public UsageSyncTracker create() {
        return new UsageSyncTracker();
    }
}
