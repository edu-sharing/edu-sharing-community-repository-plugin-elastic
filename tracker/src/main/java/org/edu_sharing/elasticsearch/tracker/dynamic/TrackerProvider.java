package org.edu_sharing.elasticsearch.tracker.dynamic;

import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;

public interface TrackerProvider {
    TransactionTrackerBase create();
}
