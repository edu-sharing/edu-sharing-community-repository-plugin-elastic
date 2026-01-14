package org.edu_sharing.elasticsearch.tracker.generic;

import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.springframework.stereotype.Component;

@Component("debug")
public class DebugTrackerProvider implements TrackerProvider {

    @Override
    public TransactionTrackerBase create() {
        return new DebugTracker();
    }
}
