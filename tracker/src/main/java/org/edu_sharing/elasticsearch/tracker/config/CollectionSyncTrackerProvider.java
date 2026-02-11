package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.CollectionSyncTracker;
import org.springframework.stereotype.Component;

@Component("collectionSyncTrackerProvider")
public class CollectionSyncTrackerProvider implements TrackerProvider<CollectionSyncTracker> {
    @Override
    public CollectionSyncTracker create() {
        return new CollectionSyncTracker();
    }
}
