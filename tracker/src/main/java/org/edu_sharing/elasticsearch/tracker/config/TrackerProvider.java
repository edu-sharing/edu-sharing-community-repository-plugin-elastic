package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;

public interface TrackerProvider {
    TransactionTrackerBase create();
}
