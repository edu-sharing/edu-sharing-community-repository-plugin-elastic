package org.edu_sharing.elasticsearch.tracker.generic;

import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;

public interface TrackerProvider {
    TransactionTrackerBase create();
}
