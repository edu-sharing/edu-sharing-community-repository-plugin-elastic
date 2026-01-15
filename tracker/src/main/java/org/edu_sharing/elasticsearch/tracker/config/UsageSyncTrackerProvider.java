package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.CollectionSyncTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.edu_sharing.elasticsearch.tracker.UsageSyncTracker;
import org.springframework.stereotype.Component;

@Component("usageSyncTrackerProvider")
public class UsageSyncTrackerProvider implements TrackerProvider {
    @Override
    public TransactionTrackerBase create() {
        return new UsageSyncTracker();
    }
}
