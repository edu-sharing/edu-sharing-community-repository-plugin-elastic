package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;

public interface TrackerProvider<T extends TransactionTrackerBase> {
    T create();
}
