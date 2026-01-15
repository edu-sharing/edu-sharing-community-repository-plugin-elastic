package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.CollectionSyncTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.springframework.stereotype.Component;

@Component("collectionSyncTrackerProvider")
public class CollectionSyncTrackerProvider implements TrackerProvider {
    @Override
    public TransactionTrackerBase create() {
        return new CollectionSyncTracker();
    }
}
